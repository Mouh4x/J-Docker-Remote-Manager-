package com.jdocker.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallbackTemplate;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.PullImageResultCallback;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

public class DockerService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DockerClient dockerClient;

    public DockerService() {
        DefaultDockerClientConfig config = DefaultDockerClientConfig
                .createDefaultConfigBuilder()
                .withDockerHost("tcp://localhost:2375")
                .build();
        this.dockerClient = DockerClientBuilder.getInstance(config).build();
    }

    public String handleListImages() throws Exception {
        List<Image> images = dockerClient.listImagesCmd().withShowAll(true).exec();

        ObjectNode root = mapper.createObjectNode();
        ArrayNode arr = mapper.createArrayNode();

        for (Image img : images) {
            ObjectNode node = mapper.createObjectNode();
            String[] repoTags = img.getRepoTags();
            String repository = null;
            String tag = null;
            if (repoTags != null && repoTags.length > 0) {
                String[] parts = repoTags[0].split(":", 2);
                repository = parts[0];
                if (parts.length > 1) {
                    tag = parts[1];
                }
            }

            node.put("repository", repository == null ? "<none>" : repository);
            node.put("tag", tag == null ? "<none>" : tag);
            node.put("id", img.getId());
            node.put("size", img.getSize() / (1024.0 * 1024.0)); // Mo
            arr.add(node);
        }

        root.set("images", arr);
        return mapper.writeValueAsString(root);
    }

    public String handleListContainers() throws Exception {
        List<Container> containers = dockerClient.listContainersCmd().withShowAll(true).exec();

        ObjectNode root = mapper.createObjectNode();
        ArrayNode arr = mapper.createArrayNode();

        for (Container c : containers) {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", c.getId());
            String name = (c.getNames() != null && c.getNames().length > 0) ? c.getNames()[0] : "";
            node.put("name", name);
            node.put("image", c.getImage());
            node.put("state", c.getState());
            arr.add(node);
        }

        root.set("containers", arr);
        return mapper.writeValueAsString(root);
    }

    public String handlePullImage(String image, String tag) throws Exception {
        if (tag == null || tag.isEmpty()) {
            tag = "latest";
        }

        dockerClient
                .pullImageCmd(image)
                .withTag(tag)
                .exec(new PullImageResultCallback())
                .awaitCompletion();

        ObjectNode root = mapper.createObjectNode();
        root.put("image", image);
        root.put("tag", tag);
        root.put("status", "pulled");
        return mapper.writeValueAsString(root);
    }

    public String handleCreateContainer(String image, String name) throws Exception {
        CreateContainerResponse response = dockerClient
                .createContainerCmd(image)
                .withName(name)
                .exec();

        ObjectNode root = mapper.createObjectNode();
        root.put("id", response.getId());
        root.put("name", name);
        root.put("image", image);
        return mapper.writeValueAsString(root);
    }

    public String handleRunContainer(String image, String name) throws Exception {
        CreateContainerResponse response = dockerClient
                .createContainerCmd(image)
                .withName(name)
                .exec();

        dockerClient.startContainerCmd(response.getId()).exec();

        ObjectNode root = mapper.createObjectNode();
        root.put("id", response.getId());
        root.put("name", name);
        root.put("image", image);
        root.put("status", "running");
        return mapper.writeValueAsString(root);
    }

    public String handleStartContainer(String idOrName) throws Exception {
        String id = resolveContainerId(idOrName);
        dockerClient.startContainerCmd(id).exec();

        ObjectNode root = mapper.createObjectNode();
        root.put("id", id);
        root.put("status", "started");
        return mapper.writeValueAsString(root);
    }

    public String handleStopContainer(String idOrName) throws Exception {
        String id = resolveContainerId(idOrName);
        dockerClient.stopContainerCmd(id).exec();

        ObjectNode root = mapper.createObjectNode();
        root.put("id", id);
        root.put("status", "stopped");
        return mapper.writeValueAsString(root);
    }

    public String handleRemoveContainer(String idOrName) throws Exception {
        String id = resolveContainerId(idOrName);
        dockerClient.removeContainerCmd(id).withForce(true).exec();

        ObjectNode root = mapper.createObjectNode();
        root.put("id", id);
        root.put("status", "removed");
        return mapper.writeValueAsString(root);
    }

    public void streamLogs(String idOrName, Consumer<String> lineConsumer) throws Exception {
        String id = resolveContainerId(idOrName);

        dockerClient.logContainerCmd(id)
                .withStdOut(true)
                .withStdErr(true)
                .withTailAll()
                .exec(new ResultCallbackTemplate<ResultCallbackTemplate<?, Frame>, Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        String line = new String(frame.getPayload(), StandardCharsets.UTF_8);
                        lineConsumer.accept(line);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        lineConsumer.accept("[LOG_ERROR] " + throwable.getMessage());
                    }
                });
    }

    private String resolveContainerId(String idOrName) {
        List<Container> containers = dockerClient.listContainersCmd().withShowAll(true).exec();
        for (Container c : containers) {
            if (c.getId().startsWith(idOrName)) {
                return c.getId();
            }
            if (c.getNames() != null) {
                for (String n : c.getNames()) {
                    if (n.equals(idOrName) || n.equals("/" + idOrName)) {
                        return c.getId();
                    }
                }
            }
        }
        throw new NotFoundException("Container not found: " + idOrName);
    }
}
