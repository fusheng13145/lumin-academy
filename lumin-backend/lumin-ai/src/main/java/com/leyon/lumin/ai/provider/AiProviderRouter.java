package com.leyon.lumin.ai.provider;

import com.leyon.lumin.ai.config.AiProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** 按 lumina.ai.provider 选择当前供应商；切换只改配置，不改 workflow。 */
@Component
public class AiProviderRouter {

    private final AiProperties properties;
    private final List<AiProvider> providers;

    public AiProviderRouter(AiProperties properties, List<AiProvider> providers) {
        this.properties = properties;
        this.providers = providers;
    }

    public String activeName() {
        return properties.getProvider();
    }

    public AiProvider active() {
        String name = activeName();
        return providers.stream()
                .filter(provider -> provider.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new AiProviderException("未找到 AI 供应商: " + name + "，可选: " + providers.stream().map(AiProvider::name).toList()));
    }
}
