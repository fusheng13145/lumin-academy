package com.leyon.lumin.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 供应商切换与 workflow 预算集中在此；密钥只从环境变量注入，不写入代码。 */
@ConfigurationProperties(prefix = "lumin.ai")
public class AiProperties {

    /** ollama | deepseek；默认本地 Ollama。 */
    private String provider = "ollama";
    private int timeoutSeconds = 120;
    private final Ollama ollama = new Ollama();
    private final DeepSeek deepseek = new DeepSeek();
    private final Workflow workflow = new Workflow();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Ollama getOllama() {
        return ollama;
    }

    public DeepSeek getDeepseek() {
        return deepseek;
    }

    public Workflow getWorkflow() {
        return workflow;
    }

    public static class Ollama {
        private String baseUrl = "http://127.0.0.1:11434";
        private String model = "deepseek-r1:8b";
        private double temperature = 0.7;
        private int maxTokens = 1200;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }
    }

    public static class DeepSeek {
        private String baseUrl = "https://api.deepseek.com";
        private String model = "deepseek-chat";
        private String apiKey = "";
        private double temperature = 0.7;
        private int maxTokens = 1200;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }
    }

    public static class Workflow {
        /** 一致性检查出现 BLOCKER 时最多重写几次；0 表示不重写。 */
        private int maxRevisionAttempts = 1;
        /** 注入提示词的长期记忆条数上限，避免上下文无限增长。 */
        private int maxMemoryFacts = 24;
        /** 单次运行最多保留的候选数（当前一轮一个候选）。 */
        private int maxCandidates = 1;

        public int getMaxRevisionAttempts() {
            return maxRevisionAttempts;
        }

        public void setMaxRevisionAttempts(int maxRevisionAttempts) {
            this.maxRevisionAttempts = maxRevisionAttempts;
        }

        public int getMaxMemoryFacts() {
            return maxMemoryFacts;
        }

        public void setMaxMemoryFacts(int maxMemoryFacts) {
            this.maxMemoryFacts = maxMemoryFacts;
        }

        public int getMaxCandidates() {
            return maxCandidates;
        }

        public void setMaxCandidates(int maxCandidates) {
            this.maxCandidates = maxCandidates;
        }
    }
}
