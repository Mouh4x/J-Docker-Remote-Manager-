package com.jdocker.common;

public class Request {
    private String action;
    private String payload; // JSON string depending on action

    public Request() {
    }

    public Request(String action, String payload) {
        this.action = action;
        this.payload = payload;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}
