package com.ptit.iot.dto.common;

public record WsEvent<T>(String event, T data) {
}
