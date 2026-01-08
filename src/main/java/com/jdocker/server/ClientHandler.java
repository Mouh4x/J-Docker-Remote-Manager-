package com.jdocker.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdocker.common.Request;
import com.jdocker.common.Response;

import java.io.*;
import java.net.Socket;

public class ClientHandler extends Thread {

    private final Socket clientSocket;
    private final ObjectMapper mapper = new ObjectMapper();
    private final DockerService dockerService = new DockerService();

    public ClientHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream()), true)) {

            String line;
            while ((line = in.readLine()) != null) {
                System.out.println("[SERVER] Received: " + line);
                try {
                    Request request = mapper.readValue(line, Request.class);
                    Response response = handleRequest(request);
                    String json = mapper.writeValueAsString(response);
                    out.println(json);
                } catch (Exception e) {
                    Response error = new Response("ERROR", "Invalid request: " + e.getMessage(), null);
                    out.println(mapper.writeValueAsString(error));
                }
            }
        } catch (IOException e) {
            System.out.println("[SERVER] Client disconnected: " + clientSocket.getRemoteSocketAddress());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private Response handleRequest(Request request) {
        String action = request.getAction();
        if (action == null) {
            return new Response("ERROR", "Missing action", null);
        }

        try {
            switch (action) {
                case "LIST_IMAGES":
                    return new Response("OK", "Images list", dockerService.handleListImages());
                case "LIST_CONTAINERS":
                    return new Response("OK", "Containers list", dockerService.handleListContainers());
                case "PULL_IMAGE": {
                    if (request.getPayload() == null) {
                        return new Response("ERROR", "Missing payload for PULL_IMAGE", null);
                    }
                    var node = mapper.readTree(request.getPayload());
                    String image = node.path("image").asText(null);
                    String tag = node.path("tag").asText(null);
                    if (image == null) {
                        return new Response("ERROR", "Missing image name", null);
                    }
                    String data = dockerService.handlePullImage(image, tag);
                    return new Response("OK", "Image pulled", data);
                }
                case "CREATE_CONTAINER": {
                    if (request.getPayload() == null) {
                        return new Response("ERROR", "Missing payload for CREATE_CONTAINER", null);
                    }
                    var node = mapper.readTree(request.getPayload());
                    String image = node.path("image").asText(null);
                    String name = node.path("name").asText(null);
                    if (image == null || name == null) {
                        return new Response("ERROR", "Missing image or name", null);
                    }
                    String data = dockerService.handleCreateContainer(image, name);
                    return new Response("OK", "Container created", data);
                }
                case "RUN_CONTAINER": {
                    if (request.getPayload() == null) {
                        return new Response("ERROR", "Missing payload for RUN_CONTAINER", null);
                    }
                    var node = mapper.readTree(request.getPayload());
                    String image = node.path("image").asText(null);
                    String name = node.path("name").asText(null);
                    if (image == null || name == null) {
                        return new Response("ERROR", "Missing image or name", null);
                    }
                    String data = dockerService.handleRunContainer(image, name);
                    return new Response("OK", "Container created and started", data);
                }
                case "START_CONTAINER": {
                    if (request.getPayload() == null) {
                        return new Response("ERROR", "Missing payload for START_CONTAINER", null);
                    }
                    var node = mapper.readTree(request.getPayload());
                    String idOrName = node.path("idOrName").asText(null);
                    if (idOrName == null) {
                        return new Response("ERROR", "Missing container idOrName", null);
                    }
                    String data = dockerService.handleStartContainer(idOrName);
                    return new Response("OK", "Container started", data);
                }
                case "STOP_CONTAINER": {
                    if (request.getPayload() == null) {
                        return new Response("ERROR", "Missing payload for STOP_CONTAINER", null);
                    }
                    var node = mapper.readTree(request.getPayload());
                    String idOrName = node.path("idOrName").asText(null);
                    if (idOrName == null) {
                        return new Response("ERROR", "Missing container idOrName", null);
                    }
                    String data = dockerService.handleStopContainer(idOrName);
                    return new Response("OK", "Container stopped", data);
                }
                case "REMOVE_CONTAINER": {
                    if (request.getPayload() == null) {
                        return new Response("ERROR", "Missing payload for REMOVE_CONTAINER", null);
                    }
                    var node = mapper.readTree(request.getPayload());
                    String idOrName = node.path("idOrName").asText(null);
                    if (idOrName == null) {
                        return new Response("ERROR", "Missing container idOrName", null);
                    }
                    String data = dockerService.handleRemoveContainer(idOrName);
                    return new Response("OK", "Container removed", data);
                }
                case "STREAM_LOGS": {
                    if (request.getPayload() == null) {
                        return new Response("ERROR", "Missing payload for STREAM_LOGS", null);
                    }
                    var node = mapper.readTree(request.getPayload());
                    String idOrName = node.path("idOrName").asText(null);
                    if (idOrName == null) {
                        return new Response("ERROR", "Missing container idOrName", null);
                    }

                    Thread logThread = new Thread(() -> {
                        try {
                            dockerService.streamLogs(idOrName, line -> {
                                try {
                                    synchronized (mapper) {
                                        Response logResp = new Response("OK", "LOG_LINE", line.trim());
                                        String json = mapper.writeValueAsString(logResp);
                                        synchronized (ClientHandler.this) {
                                            // on ré-ouvre un writer pour ce socket
                                            try (PrintWriter out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream()), true)) {
                                                out.println(json);
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {
                                }
                            });
                        } catch (Exception e) {
                            // en cas d'erreur de stream, on arrête le thread
                        }
                    });
                    logThread.setDaemon(true);
                    logThread.start();

                    return new Response("OK", "Log streaming started", null);
                }
                default:
                    return new Response("ERROR", "Unknown action: " + action, null);
            }
        } catch (Exception e) {
            return new Response("ERROR", "Server error: " + e.getMessage(), null);
        }
    }
}
