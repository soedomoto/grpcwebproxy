package inc.apems.springboot.grpcweb;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MethodRegistryService {
    private ImmutableList<Descriptors.FileDescriptor> fileDescriptors;

    public ImmutableList<Descriptors.FileDescriptor> getFileDescriptors() {
        return fileDescriptors;
    }

    public void setFileDescriptors(ImmutableList<Descriptors.FileDescriptor> fileDescriptors) {
        this.fileDescriptors = fileDescriptors;
    }

    /**
     * Lists all of the services found in the file descriptors.
     */
    public Iterable<Descriptors.ServiceDescriptor> listServices() {
        ArrayList<Descriptors.ServiceDescriptor> serviceDescriptors = new ArrayList<>();
        fileDescriptors.forEach(fileDescriptor -> serviceDescriptors.addAll(fileDescriptor.getServices()));
        return serviceDescriptors;
    }

    /**
     * Lists all the known message types.
     */
    public ImmutableSet<Descriptors.Descriptor> listMessageTypes() {
        ImmutableSet.Builder<Descriptors.Descriptor> resultBuilder = ImmutableSet.builder();
        fileDescriptors.forEach(d -> resultBuilder.addAll(d.getMessageTypes()));
        return resultBuilder.build();
    }

    private Descriptors.ServiceDescriptor findService(String serviceName, String packageName) {
        for (Descriptors.FileDescriptor fileDescriptor : fileDescriptors) {
            if (!fileDescriptor.getPackage().equals(packageName)) {
                // Package does not match this file, ignore.
                continue;
            }

            Descriptors.ServiceDescriptor serviceDescriptor = fileDescriptor.findServiceByName(serviceName);
            if (serviceDescriptor != null) {
                return serviceDescriptor;
            }
        }
        throw new IllegalArgumentException("Can't find service with name: " + serviceName);
    }

    public Descriptors.MethodDescriptor resolveServiceMethod(
            String serviceName, String methodName, String packageName) {
        Descriptors.ServiceDescriptor service = findService(serviceName, packageName);
        Descriptors.MethodDescriptor method = service.findMethodByName(methodName);
        if (method == null) {
            throw new IllegalArgumentException(
                    "Can't find method " + methodName + " in service " + serviceName);
        }

        return method;
    }







//    private Map<String, List<DescriptorProtos.MethodDescriptorProto>> methodDescriptors = new HashMap<>();
//    private Map<String, DescriptorProtos.DescriptorProto> messageDescriptors = new HashMap<>();
//
//    public void setMethodDescriptors(Map<String, List<DescriptorProtos.MethodDescriptorProto>> methodDescriptors) {
//        this.methodDescriptors = methodDescriptors;
//    }
//
//    public Map<String, List<DescriptorProtos.MethodDescriptorProto>> getMethodDescriptors() {
//        return methodDescriptors;
//    }
//
//    public List<DescriptorProtos.MethodDescriptorProto> getMethodDescriptorsOf(String fullnameMethod) {
//        return methodDescriptors.get(fullnameMethod);
//    }
//
//    public DescriptorProtos.MethodDescriptorProto getMethodDescriptorOf(String fullnameMethod) {
//        List<DescriptorProtos.MethodDescriptorProto> ls = getMethodDescriptorsOf(fullnameMethod);
//        if (! ls.isEmpty()) return ls.get(0);
//        return null;
//    }
//
//    public void setMessageDescriptors(Map<String, DescriptorProtos.DescriptorProto> messageDescriptors) {
//        this.messageDescriptors = messageDescriptors;
//    }
//
//    public Map<String, DescriptorProtos.DescriptorProto> getMessageDescriptors() {
//        return messageDescriptors;
//    }
//
//    public DescriptorProtos.DescriptorProto getMessageDescriptorOf(String name) {
//        return messageDescriptors.get(name);
//    }
}
