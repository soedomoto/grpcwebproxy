package inc.apems.springboot.grpcweb;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.*;
import io.grpc.Channel;
import io.grpc.reflection.v1alpha.ListServiceResponse;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class GrpcServiceLister implements CommandLineRunner {
    static Logger logger = LoggerFactory.getLogger(GrpcServiceLister.class);

    @Autowired
    Channels channels;
    @Autowired
    private MethodRegistryService methodRegistryService;

    static class ListServicesHandler implements StreamObserver<ServerReflectionResponse> {
        private final SettableFuture<ImmutableList<String>> resultFuture;
        private StreamObserver<ServerReflectionRequest> requestStream;

        private ListServicesHandler() {
            resultFuture = SettableFuture.create();
        }

        ListenableFuture<ImmutableList<String>> start(
                StreamObserver<ServerReflectionRequest> requestStream) {
            this.requestStream = requestStream;
            requestStream.onNext(ServerReflectionRequest.newBuilder()
                    .setListServices("")  // Not sure what this is for, appears to be ignored.
                    .build());
            return resultFuture;
        }

        @Override
        public void onNext(ServerReflectionResponse serverReflectionResponse) {
            ServerReflectionResponse.MessageResponseCase responseCase = serverReflectionResponse.getMessageResponseCase();
            switch (responseCase) {
                case LIST_SERVICES_RESPONSE:
                    handleListServiceRespones(serverReflectionResponse.getListServicesResponse());
                    break;
                default:
//                        logger.warn("Got unknown reflection response type: " + responseCase);
                    break;
            }
        }

        @Override
        public void onError(Throwable t) {
            resultFuture.setException(new RuntimeException("Error in server reflection rpc while listing services", t));
        }

        @Override
        public void onCompleted() {
            if (!resultFuture.isDone()) {
//                    logger.error("Unexpected completion of server reflection rpc while listing services");
                resultFuture.setException(new RuntimeException("Unexpected end of rpc"));
            }
        }

        private void handleListServiceRespones(ListServiceResponse response) {
            ImmutableList.Builder<String> servicesBuilder = ImmutableList.builder();
            response.getServiceList().forEach(service -> servicesBuilder.add(service.getName()));
            resultFuture.set(servicesBuilder.build());
            requestStream.onCompleted();
        }
    }

    static class LookupServiceHandler implements StreamObserver<ServerReflectionResponse> {
        private final SettableFuture<DescriptorProtos.FileDescriptorSet> resultFuture;
        private final String serviceName;
        private final HashSet<String> requestedDescriptors;
        private final HashMap<String, DescriptorProtos.FileDescriptorProto> resolvedDescriptors;
        private StreamObserver<ServerReflectionRequest> requestStream;

        // Used to notice when we've received all the files we've asked for and we can end the rpc.
        private int outstandingRequests;

        private LookupServiceHandler(String serviceName) {
            this.serviceName = serviceName;
            this.resultFuture = SettableFuture.create();
            this.resolvedDescriptors = new HashMap<>();
            this.requestedDescriptors = new HashSet<>();
            this.outstandingRequests = 0;
        }

        ListenableFuture<DescriptorProtos.FileDescriptorSet> start(
                StreamObserver<ServerReflectionRequest> requestStream) {
            this.requestStream = requestStream;
            requestStream.onNext(requestForSymbol(serviceName));
            ++outstandingRequests;
            return resultFuture;
        }

        @Override
        public void onNext(ServerReflectionResponse response) {
            ServerReflectionResponse.MessageResponseCase responseCase = response.getMessageResponseCase();
            switch (responseCase) {
                case FILE_DESCRIPTOR_RESPONSE:
                    ImmutableSet<DescriptorProtos.FileDescriptorProto> descriptors =
                            parseDescriptors(response.getFileDescriptorResponse().getFileDescriptorProtoList());
                    descriptors.forEach(d -> resolvedDescriptors.put(d.getName(), d));
                    descriptors.forEach(d -> processDependencies(d));
                    break;
                default:
//                    logger.warn("Got unknown reflection response type: " + responseCase);
                    break;
            }
        }

        @Override
        public void onError(Throwable t) {
            resultFuture.setException(new RuntimeException("Reflection lookup rpc failed for: " + serviceName, t));
        }

        @Override
        public void onCompleted() {
            if (!resultFuture.isDone()) {
//                logger.error("Unexpected completion of the server reflection rpc");
                resultFuture.setException(new RuntimeException("Unexpected end of rpc"));
            }
        }

        private ImmutableSet<DescriptorProtos.FileDescriptorProto> parseDescriptors(List<ByteString> descriptorBytes) {
            ImmutableSet.Builder<DescriptorProtos.FileDescriptorProto> resultBuilder = ImmutableSet.builder();
            for (ByteString fileDescriptorBytes : descriptorBytes) {
                try {
                    resultBuilder.add(DescriptorProtos.FileDescriptorProto.parseFrom(fileDescriptorBytes));
                } catch (InvalidProtocolBufferException e) {
//                    logger.warn("Failed to parse bytes as file descriptor proto");
                }
            }
            return resultBuilder.build();
        }

        private void processDependencies(DescriptorProtos.FileDescriptorProto fileDescriptor) {
//            logger.debug("Processing deps of descriptor: " + fileDescriptor.getName());
            fileDescriptor.getDependencyList().forEach(dep -> {
                if (!resolvedDescriptors.containsKey(dep) && !requestedDescriptors.contains(dep)) {
                    requestedDescriptors.add(dep);
                    ++outstandingRequests;
                    requestStream.onNext(requestForDescriptor(dep));
                }
            });

            --outstandingRequests;
            if (outstandingRequests == 0) {
//                logger.debug("Retrieved service definition for [{}] by reflection", serviceName);
                resultFuture.set(DescriptorProtos.FileDescriptorSet.newBuilder()
                        .addAllFile(resolvedDescriptors.values())
                        .build());
                requestStream.onCompleted();
            }
        }

        private static ServerReflectionRequest requestForDescriptor(String name) {
            return ServerReflectionRequest.newBuilder()
                    .setFileByFilename(name)
                    .build();
        }

        private static ServerReflectionRequest requestForSymbol(String symbol) {
            return ServerReflectionRequest.newBuilder()
                    .setFileContainingSymbol(symbol)
                    .build();
        }
    }

    private static ImmutableMap<String, DescriptorProtos.FileDescriptorProto> computeDescriptorProtoIndex(DescriptorProtos.FileDescriptorSet fileDescriptorSet) {
        ImmutableMap.Builder<String, DescriptorProtos.FileDescriptorProto> resultBuilder = ImmutableMap.builder();

        List<DescriptorProtos.FileDescriptorProto> descriptorProtos = fileDescriptorSet.getFileList();
        descriptorProtos.forEach(descriptorProto -> resultBuilder.put(descriptorProto.getName(), descriptorProto));

        return resultBuilder.build();
    }

    private static Descriptors.FileDescriptor descriptorFromProto(
            DescriptorProtos.FileDescriptorProto descriptorProto,
            ImmutableMap<String, DescriptorProtos.FileDescriptorProto> descriptorProtoIndex,
            Map<String, Descriptors.FileDescriptor> descriptorCache) throws Descriptors.DescriptorValidationException {
        // First, check the cache.
        String descriptorName = descriptorProto.getName();
        if (descriptorCache.containsKey(descriptorName)) {
            return descriptorCache.get(descriptorName);
        }

        // Then, fetch all the required dependencies recursively.
        ImmutableList.Builder<Descriptors.FileDescriptor> dependencies = ImmutableList.builder();
        ProtocolStringList protocolStringList = descriptorProto.getDependencyList();
        protocolStringList.forEach(dependencyName -> {
            if (!descriptorProtoIndex.containsKey(dependencyName)) {
                throw new IllegalArgumentException("Can't find dependency: " + dependencyName);
            }
            DescriptorProtos.FileDescriptorProto dependencyProto = descriptorProtoIndex.get(dependencyName);
            try {
                dependencies.add(descriptorFromProto(dependencyProto, descriptorProtoIndex, descriptorCache));
            } catch (Descriptors.DescriptorValidationException e) {
                logger.warn(e.getMessage());
            }
        });

        // Finally, construct the actual descriptor.
        Descriptors.FileDescriptor[] empty = new Descriptors.FileDescriptor[0];

        return Descriptors.FileDescriptor.buildFrom(descriptorProto, dependencies.build().toArray(empty));
    }

    private void parseNestedType(List<DescriptorProtos.DescriptorProto> nestedTypeList, String parent, Map<String, DescriptorProtos.DescriptorProto> messageDescriptors) {
        for (DescriptorProtos.DescriptorProto descriptorProto : nestedTypeList) {
            String fullnameMessage = parent + "." + descriptorProto.getName();
            messageDescriptors.put(fullnameMessage, descriptorProto);

            parseNestedType(descriptorProto.getNestedTypeList(), fullnameMessage, messageDescriptors);
        }
    }

    @Override
    public void run(String... args) throws Exception {
//        Map<String, List<DescriptorProtos.MethodDescriptorProto>> methodDescriptors = new HashMap<>();
//        Map<String, DescriptorProtos.DescriptorProto> messageDescriptors = new HashMap<>();

        Map<String, Descriptors.FileDescriptor> descriptorCache = new HashMap<>();
        ImmutableList.Builder<Descriptors.FileDescriptor> result = ImmutableList.builder();

        for (Channel channel : channels.getChannels()) {
            ListServicesHandler lsHandler = new ListServicesHandler();
            StreamObserver<ServerReflectionRequest> lsResStream = ServerReflectionGrpc.newStub(channel).serverReflectionInfo(lsHandler);
            ImmutableList<String> serviceNames = lsHandler.start(lsResStream).get();

            for (String serviceName : serviceNames) {
                LookupServiceHandler luHandler = new LookupServiceHandler(serviceName);
                StreamObserver<ServerReflectionRequest> luResStream = ServerReflectionGrpc.newStub(channel)
                        .serverReflectionInfo(luHandler);

                DescriptorProtos.FileDescriptorSet descriptorSet = luHandler.start(luResStream).get();

                ImmutableMap<String, DescriptorProtos.FileDescriptorProto> descriptorProtoIndex = computeDescriptorProtoIndex(descriptorSet);

                List<DescriptorProtos.FileDescriptorProto> descriptorProtos = descriptorSet.getFileList();
                for (DescriptorProtos.FileDescriptorProto descriptorProto : descriptorProtos) {
                    try {
                        result.add(descriptorFromProto(descriptorProto, descriptorProtoIndex, descriptorCache));
                    } catch (Descriptors.DescriptorValidationException e) {
                        logger.warn(e.getMessage());
                        continue;
                    }
                }


//                DescriptorProtos.FileDescriptorSet fileDescriptors = luHandler.start(luResStream).get();

//                for (DescriptorProtos.FileDescriptorProto fileDescriptorProto : fileDescriptors.getFileList()) {
//                    for (DescriptorProtos.DescriptorProto descriptorProto : fileDescriptorProto.getMessageTypeList()) {
//                        String fullnameMessage = fileDescriptorProto.getPackage() + "." + descriptorProto.getName();
//                        messageDescriptors.put(fullnameMessage, descriptorProto);
//                        parseNestedType(descriptorProto.getNestedTypeList(), fullnameMessage, messageDescriptors);
//                    }
//
//                    for (DescriptorProtos.ServiceDescriptorProto serviceDescriptorProto : fileDescriptorProto.getServiceList()) {
//                        for (DescriptorProtos.MethodDescriptorProto methodDescriptorProto : serviceDescriptorProto.getMethodList()) {
//                            String fullnameMethod = serviceName + "/" + methodDescriptorProto.getName();
//                            if (! methodDescriptors.containsKey(fullnameMethod))
//                                methodDescriptors.put(fullnameMethod, new ArrayList<>());
//                            methodDescriptors.get(fullnameMethod).add(methodDescriptorProto);
//                        }
//                    }
//                }
            }
        }

        methodRegistryService.setFileDescriptors(ImmutableList.copyOf(result.build()));
//        methodRegistryService.setMethodDescriptors(methodDescriptors);
//        methodRegistryService.setMessageDescriptors(messageDescriptors);
    }
}
