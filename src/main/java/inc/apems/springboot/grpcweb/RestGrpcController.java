package inc.apems.springboot.grpcweb;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.*;
import inc.apems.springboot.grpcweb.grpc.DynamicClient;
import io.grpc.CallOptions;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/")
public class RestGrpcController {
    static Logger logger = LoggerFactory.getLogger(RestGrpcController.class);

    @Autowired
    ApplicationContext applicationContext;
    @Autowired
    MethodRegistryService methodRegistryService;
    @Autowired
    Channels channels;
    @Value("${proxy.request-timeout}")
    String requestTimeout = "0";

    public static int byteArrayToInt(byte[] b) {
        return   b[3] & 0xFF |
                (b[2] & 0xFF) << 8 |
                (b[1] & 0xFF) << 16 |
                (b[0] & 0xFF) << 24;
    }

    public static byte[] intToByteArray(int a) {
        return new byte[] {
                (byte) ((a >> 24) & 0xFF),
                (byte) ((a >> 16) & 0xFF),
                (byte) ((a >> 8) & 0xFF),
                (byte) (a & 0xFF)
        };
    }

    @CrossOrigin(origins = "*", allowedHeaders = "*")
    @PostMapping(value = "/{clazz}/{method}", consumes = "application/grpc-web+proto", produces = "application/grpc-web+proto")
    ResponseEntity<ResponseBodyEmitter> postBinary(
            @RequestBody(required = false) byte[] buffer,
//            HttpServletRequest request,
            HttpServletResponse response,
            @RequestHeader Map<String, String> headers,
            @PathVariable("clazz") String clazz,
            @PathVariable("method") String method) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(Integer.parseInt(requestTimeout) * 1000L);

        Executors.newSingleThreadExecutor()
                .execute(() -> {
                    String[] clazzes = clazz.split("\\.");
                    if (clazzes.length > 0) {
                        String packageName = String.join(".", Arrays.copyOf(clazzes, clazzes.length - 1));
                        Descriptors.MethodDescriptor methodDescriptor = methodRegistryService.resolveServiceMethod(clazzes[clazzes.length - 1], method, packageName);

                        ImmutableList.Builder<DynamicMessage> requests = ImmutableList.builder();
                        byte[] upbuffer = Arrays.copyOfRange(buffer, 4, buffer.length);
                        int p = 0;
                        while (p < upbuffer.length) {
                            int len = upbuffer[p];
                            byte[] data = Arrays.copyOfRange(upbuffer, p + 1, p + 1 + len);

                            try {
                                DynamicMessage inp = DynamicMessage.newBuilder(methodDescriptor.getInputType())
                                        .mergeFrom(data, ExtensionRegistryLite.getEmptyRegistry())
                                        .build();
                                requests.add(inp);
                            } catch (InvalidProtocolBufferException e) {
                                emitter.completeWithError(e);
                            }

                            p = p + 1 + len;
                        }

                        DynamicClient client = DynamicClient.create(methodDescriptor, channels.getChannel());
                        client.call(requests.build(), new StreamObserver<DynamicMessage>() {
                            @Override
                            public void onNext(DynamicMessage message) {
                                try {
                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
//                                    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
                                    message.writeTo(baos);
                                    byte[] ba = baos.toByteArray();

                                    ByteBuffer trailerBuffer = ByteBuffer.wrap(new byte[5]);
                                    trailerBuffer.put((byte) 0);
                                    trailerBuffer.put(intToByteArray(ba.length));

                                    emitter.send(trailerBuffer.array(), new MediaType("application", "grpc-web+proto"));
                                    emitter.send(ba, new MediaType("application", "grpc-web+proto"));
                                } catch (IOException e) {
                                    emitter.completeWithError(e);
                                }
                            }

                            @Override
                            public void onError(Throwable t) {
                                emitter.completeWithError(t);
                            }

                            @Override
                            public void onCompleted() {
                                try {
                                    byte[] trailer = "grpc-status: 0".getBytes(StandardCharsets.UTF_8);

                                    ByteBuffer trailerBuffer = ByteBuffer.wrap(new byte[5]);
                                    trailerBuffer.put((byte) ((1 & 0xff) >> 7));
                                    trailerBuffer.put(intToByteArray(trailer.length));

                                    emitter.send(trailerBuffer.array(), new MediaType("application", "grpc-web+proto"));
                                    emitter.send(trailer, new MediaType("application", "grpc-web+proto"));
                                } catch (IOException e) {
                                    emitter.completeWithError(e);
                                }

                                emitter.complete();
                            }
                        }, CallOptions.DEFAULT);
                    }
                });

        return ResponseEntity.ok()
                .contentType(new MediaType("application", "grpc-web+proto"))
                .header("Access-Control-Allow-Credentials", "true")
                .header("Access-Control-Expose-Headers", "Vary, Access-Control-Allow-Origin, Access-Control-Allow-Credentials, Date, Content-Type, Grpc-Accept-Encoding, grpc-status, grpc-message")
                .header("Grpc-Accept-Encoding", "gzip")
                .header("Transfer-Encoding", "chunked")
                .body(emitter);


//            CodedOutputStream cos = CodedOutputStream.newInstance(response.getOutputStream());
//
//            String[] clazzes = clazz.split("\\.");
//            if (clazzes.length > 0) {
//                String packageName = String.join(".", Arrays.copyOf(clazzes, clazzes.length - 1));
//                Descriptors.MethodDescriptor methodDescriptor = methodRegistryService.resolveServiceMethod(clazzes[clazzes.length - 1], method, packageName);
//
//                ImmutableList.Builder<DynamicMessage> requests = ImmutableList.builder();
//                byte[] upbuffer = Arrays.copyOfRange(buffer, 4, buffer.length);
//                int p = 0;
//                while (p < upbuffer.length) {
//                    int len = upbuffer[p];
//                    byte[] data = Arrays.copyOfRange(upbuffer, p+1, p+1+len);
//
//                    DynamicMessage inp = DynamicMessage.newBuilder(methodDescriptor.getInputType())
//                            .mergeFrom(data, ExtensionRegistryLite.getEmptyRegistry())
//                            .build();
//                    requests.add(inp);
//
//                    p = p+1+len;
//                }
//
//                DynamicClient client = DynamicClient.create(methodDescriptor, channels.getChannel());
//                client.call(requests.build(), new StreamObserver<DynamicMessage>() {
//                    @Override
//                    public void onNext(DynamicMessage message) {
//                        try {
//                            message.writeTo(cos);
//                        } catch (IOException e) {
//                            e.printStackTrace();
//                        }
//                    }
//
//                    @Override
//                    public void onError(Throwable t) {
//                        try {
//                            response.setStatus(500);
//                            response.flushBuffer();
//                        } catch (IOException e) {
//                            e.printStackTrace();
//                        }
//                    }
//
//                    @Override
//                    public void onCompleted() {
//                        try {
//                            response.flushBuffer();
//                        } catch (IOException e) {
//                            e.printStackTrace();
//                        }
//                    }
//                }, CallOptions.DEFAULT);
//            }
//        } catch (Exception e) {
//            response.setStatus(500);
//            try {
//                response.flushBuffer();
//            } catch (IOException ioException) {
//                ioException.printStackTrace();
//            }
//        }
    }

//    @CrossOrigin(origins = "*", allowedHeaders = "*")
//    @PostMapping(value = "/{clazz}/{method}", consumes = "application/grpc-web-text-json", produces = "application/grpc-web-text-json")
//    ResponseEntity<StreamingResponseBody> postJson(
//            @RequestBody byte[] data,
//            @RequestHeader Map<String, String> headers,
//            @PathVariable("clazz") String clazz,
//            @PathVariable("method") String method) throws IOException {
//        StreamingResponseBody stream = out -> {
//            try {
//                Writer writer = new BufferedWriter(new OutputStreamWriter(out));
//
//                DescriptorProtos.MethodDescriptorProto descriptor = methodRegistryService.getMethodDescriptorOf(clazz + "/" + method);
//                if (descriptor != null) {
//                    Message.Builder reqMsgBld = methodRegistryService
//                            .getMessageDescriptorOf(descriptor.getInputType().substring(1))
//                            .toBuilder();
//                    Message.Builder respMsgBld = methodRegistryService
//                            .getMessageDescriptorOf(descriptor.getOutputType().substring(1))
//                            .toBuilder();
//
//                    String strData = new String(data);
//                    JsonFormat.parser().merge(strData, reqMsgBld);
//
//                    MethodDescriptor.MethodType type = MethodDescriptor.MethodType.UNARY;
//                    if (descriptor.getServerStreaming() && descriptor.getClientStreaming())
//                        type = MethodDescriptor.MethodType.BIDI_STREAMING;
//                    if (descriptor.getServerStreaming()) type = MethodDescriptor.MethodType.SERVER_STREAMING;
//                    if (descriptor.getClientStreaming()) type = MethodDescriptor.MethodType.CLIENT_STREAMING;
//
//                    MethodDescriptor<Message, Message> grpcMethodDesc = MethodDescriptor.<Message, Message>newBuilder()
//                            .setType(type)
//                            .setFullMethodName(clazz + "/" + method)
//                            .setRequestMarshaller(ProtoUtils.marshaller(reqMsgBld.buildPartial()))
//                            .setResponseMarshaller(ProtoUtils.marshaller(respMsgBld.buildPartial()))
//                            .build();
//
//                    ClientCall<Message, Message> call = channels.getChannel().newCall(grpcMethodDesc, CallOptions.DEFAULT);
//                    StreamObserver<Message> observer = new StreamObserver<Message>() {
//                        @Override
//                        public void onNext(Message message) {
//                            try {
//                                Message.Builder respMsg = respMsgBld.buildPartial().newBuilderForType();
//                                respMsg.mergeFrom(message);
//                                String msg = JsonFormat.printer().print(message);
//                                writer.write(msg);
//                                writer.flush();
//                            } catch (Exception e) {
//                                int a = 1;
//                            }
//                        }
//
//                        @Override
//                        public void onError(Throwable throwable) {
//
//                        }
//
//                        @Override
//                        public void onCompleted() {
////                            try {
////                                writer.flush();
////                            } catch (Exception e) {
////                                int a = 1;
////                            }
//                        }
//                    };
//
//
//
//                    if (type == MethodDescriptor.MethodType.UNARY) ClientCalls.asyncUnaryCall(call, reqMsgBld.build(), observer);
//                    else if (type == MethodDescriptor.MethodType.SERVER_STREAMING) ClientCalls.asyncServerStreamingCall(call, reqMsgBld.build(), observer);
////                else if (type == MethodDescriptor.MethodType.CLIENT_STREAMING) ClientCalls.asyncClientStreamingCall(call, reqMsgBld.build(), observer);
////                else if (type == MethodDescriptor.MethodType.BIDI_STREAMING) ClientCalls.asyncBidiStreamingCall(call, reqMsgBld.build(), observer);
//                }
//            } catch (Exception e) {
//                int a = 1;
//            }
//        };
//
//        return ResponseEntity.ok()
//                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=data.json")
//                .header("access-control-expose-headers", "custom-header-1,grpc-status,grpc-message")
//                .header("content-type", "application/grpc-web-text-json")
//                .header("grpc-accept-encoding", "gzip")
//                .header("grpc-encoding", "identity")
//                .body(stream);
//
//
//        /*response.addHeader("access-control-expose-headers", "custom-header-1,grpc-status,grpc-message");
//        response.addHeader("content-type", "application/grpc-web-text-json");
//        response.addHeader("grpc-accept-encoding", "gzip");
//        response.addHeader("grpc-encoding", "identity");
//
//        PrintWriter out = response.getWriter();
//
////        StreamingResponseBody stream = o -> {
//            try {
//                DescriptorProtos.MethodDescriptorProto descriptor = methodRegistryService.getMethodDescriptorOf(clazz + "/" + method);
//                if (descriptor != null) {
//                    Message.Builder reqMsgBld = methodRegistryService
//                            .getMessageDescriptorOf(descriptor.getInputType().substring(1))
//                            .toBuilder();
//                    Message.Builder respMsgBld = methodRegistryService
//                            .getMessageDescriptorOf(descriptor.getOutputType().substring(1))
//                            .toBuilder();
//
//                    String strData = new String(data);
//                    JsonFormat.parser().merge(strData, reqMsgBld);
//
//                    MethodDescriptor.MethodType type = MethodDescriptor.MethodType.UNARY;
//                    if (descriptor.getServerStreaming() && descriptor.getClientStreaming())
//                        type = MethodDescriptor.MethodType.BIDI_STREAMING;
//                    if (descriptor.getServerStreaming()) type = MethodDescriptor.MethodType.SERVER_STREAMING;
//                    if (descriptor.getClientStreaming()) type = MethodDescriptor.MethodType.CLIENT_STREAMING;
//
//                    MethodDescriptor<Message, Message> grpcMethodDesc = MethodDescriptor.<Message, Message>newBuilder()
//                            .setType(type)
//                            .setFullMethodName(clazz + "/" + method)
//                            .setRequestMarshaller(ProtoUtils.marshaller(reqMsgBld.buildPartial()))
//                            .setResponseMarshaller(ProtoUtils.marshaller(respMsgBld.buildPartial()))
//                            .build();
//
//                    ClientCall<Message, Message> call = channels.getChannel().newCall(grpcMethodDesc, CallOptions.DEFAULT);
//                    StreamObserver<Message> observer = new StreamObserver<Message>() {
//                        @Override
//                        public void onNext(Message message) {
//                            try {
//                                String msg = JsonFormat.printer().print(message);
//                                out.write(msg);
//                            } catch (Exception e) {
//                                int a = 1;
//                            }
//                        }
//
//                        @Override
//                        public void onError(Throwable throwable) {
//
//                        }
//
//                        @Override
//                        public void onCompleted() {
//                            try {
//                                out.close();
//                            } catch (Exception e) {
//                                int a = 1;
//                            }
//                        }
//                    };
//
//                    if (type == MethodDescriptor.MethodType.UNARY) ClientCalls.asyncUnaryCall(call, reqMsgBld.build(), observer);
//                    else if (type == MethodDescriptor.MethodType.SERVER_STREAMING) ClientCalls.asyncServerStreamingCall(call, reqMsgBld.build(), observer);
////                else if (type == MethodDescriptor.MethodType.CLIENT_STREAMING) ClientCalls.asyncClientStreamingCall(call, reqMsgBld.build(), observer);
////                else if (type == MethodDescriptor.MethodType.BIDI_STREAMING) ClientCalls.asyncBidiStreamingCall(call, reqMsgBld.build(), observer);
//                }
//            } catch (Exception e) {
//                int a = 1;
//            }
////        };
//
////        return new ResponseEntity(stream, HttpStatus.OK);*/
//    }

    @ResponseBody
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public String handleHttpMediaTypeNotAcceptableException() {
        return "acceptable MIME type:" + MediaType.ALL_VALUE;
    }
}
