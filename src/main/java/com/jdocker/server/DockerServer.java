package com.jdocker.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class DockerServer {

    private final int port;

    public DockerServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[SERVER] Listening on port " + port);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[SERVER] New client connected: " + clientSocket.getRemoteSocketAddress());
                ClientHandler handler = new ClientHandler(clientSocket);
                handler.start();
            }
        }
    }

    public static void main(String[] args) {
        int port = 5000;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        DockerServer server = new DockerServer(port);
        try {
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
