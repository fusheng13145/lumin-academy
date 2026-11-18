package com.leyon.lumin.ai.api;

public record AiHealthView(String provider, String model, boolean available, String message) {
}
