package com.jdocker.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdocker.common.Request;
import com.jdocker.common.Response;

import java.io.*;
import java.net.Socket;
import java.util.Scanner;

public class DockerClientCLI {

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 5000;

        if (args.length >= 1) {
            host = args[0];
        }
        if (args.length >= 2) {
            port = Integer.parseInt(args[1]);
        }

        ObjectMapper mapper = new ObjectMapper();

        try (Socket socket = new Socket(host, port);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
             Scanner scanner = new Scanner(System.in)) {

            System.out.println("Connecté au serveur " + host + ":" + port);
            System.out.println("Commandes disponibles : images, containers, pull <image>[:tag], run <image> <name>, stop <name>, rm <name>, logs <name>, exit");

            Thread readerThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        try {
                            Response response = mapper.readValue(line, Response.class);
                            if ("LOG_LINE".equals(response.getMessage())) {
                                if (response.getData() != null) {
                                    System.out.println(response.getData());
                                }
                            } else {
                                System.out.println("[SERVER] status=" + response.getStatus() + " message=" + response.getMessage());
                                if (response.getData() != null) {
                                    System.out.println("[DATA] " + response.getData());
                                }
                            }
                        } catch (Exception e) {
                            System.out.println("[SERVER RAW] " + line);
                        }
                    }
                } catch (IOException e) {
                    System.out.println("Connexion fermée par le serveur.");
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();

            while (true) {
                System.out.print("jdocker> ");
                String input = scanner.nextLine().trim();
                if ("exit".equalsIgnoreCase(input)) {
                    break;
                }

                String[] parts = input.split("\\s+");
                String cmd = parts[0].toLowerCase();

                Request request;
                switch (cmd) {
                    case "images":
                        request = new Request("LIST_IMAGES", null);
                        break;
                    case "containers":
                        request = new Request("LIST_CONTAINERS", null);
                        break;
                    case "pull":
                        if (parts.length < 2) {
                            System.out.println("Usage: pull <image>[:tag]");
                            continue;
                        }
                        String imageArg = parts[1];
                        String image = imageArg;
                        String tag = "latest";
                        int colon = imageArg.indexOf(":");
                        if (colon >= 0) {
                            image = imageArg.substring(0, colon);
                            tag = imageArg.substring(colon + 1);
                        }
                        String pullPayload = String.format("{\"image\":\"%s\",\"tag\":\"%s\"}", image, tag);
                        request = new Request("PULL_IMAGE", pullPayload);
                        break;
                    case "run":
                        if (parts.length < 3) {
                            System.out.println("Usage: run <image> <name>");
                            continue;
                        }
                        String runImage = parts[1];
                        String runName = parts[2];
                        String createPayload = String.format("{\"image\":\"%s\",\"name\":\"%s\"}", runImage, runName);
                        request = new Request("RUN_CONTAINER", createPayload);
                        break;
                    case "stop":
                        if (parts.length < 2) {
                            System.out.println("Usage: stop <nameOrId>");
                            continue;
                        }
                        String stopName = parts[1];
                        String stopPayload = String.format("{\"idOrName\":\"%s\"}", stopName);
                        request = new Request("STOP_CONTAINER", stopPayload);
                        break;
                    case "rm":
                        if (parts.length < 2) {
                            System.out.println("Usage: rm <nameOrId>");
                            continue;
                        }
                        String rmName = parts[1];
                        String rmPayload = String.format("{\"idOrName\":\"%s\"}", rmName);
                        request = new Request("REMOVE_CONTAINER", rmPayload);
                        break;
                    case "logs":
                        if (parts.length < 2) {
                            System.out.println("Usage: logs <nameOrId>");
                            continue;
                        }
                        String logName = parts[1];
                        String logPayload = String.format("{\"idOrName\":\"%s\"}", logName);
                        request = new Request("STREAM_LOGS", logPayload);
                        break;
                    default:
                        System.out.println("Commande inconnue. Utilisez: images, containers, pull, run, stop, rm, logs, exit");
                        continue;
                }

                String json = mapper.writeValueAsString(request);
                out.println(json);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
