package io.wifi.habitrainsurvive.economy;

public final class VirtualProxyEconomyConfig {
    public boolean enabled = false;
    public String bindAddress = "127.0.0.1";
    public int port = 20091;
    public String secret = "";

    public void validate() {
        if (!enabled) return;
        if (bindAddress == null || bindAddress.isBlank()) throw new IllegalArgumentException("金币接口监听地址不能为空");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("金币接口端口必须为1到65535");
        if (secret == null || secret.length() < 32 || secret.indexOf('\n') >= 0 || secret.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("金币接口密钥必须至少32位且不能包含换行");
        }
    }
}
