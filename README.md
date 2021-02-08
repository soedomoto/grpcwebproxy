# gRPC Web Proxy
Small reverse proxy that can front existing gRPC servers and expose their functionality using gRPC-Web protocol, allowing for the gRPC services to be consumed from browsers

## Installing

### Building from source
To build, you need to have JDK >= 1.8 and Maven >= 2.*, and call `mvn clean package`:

```sh
git clone https://github.com/soedomoto/grpcwebproxy.git
cd grpcwebproxy
mvn clean package
```

## Running

For example, to only run the HTTP server, run the following:

```sh
java -jar ./target/grpcwebproxy-0.1.jar Dspring-boot.run.arguments=--server.port=8085,--backend-address=localhost:9090,--request-timeout=3000
```