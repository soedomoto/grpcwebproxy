package inc.apems.springboot.grpcweb;

import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.ArrayList;
import java.util.List;

public class Channels {
    private List<ManagedChannel> channels = new ArrayList<>();

    public Channels(String[] addresses) {
        for (String address : addresses) {
            String[] hostport = address.split(":");
            String host = hostport.length > 0 ? hostport[0] : "localhost";
            Integer port = Integer.valueOf(hostport.length > 1 ? hostport[1] : "9090");
            channels.add(ManagedChannelBuilder.forAddress(host, port).usePlaintext().build());
        }
    }

    public List<ManagedChannel> getChannels() {
        return channels;
    }

    public ManagedChannel getChannel() {
        return channels.get(0);
    }
}
