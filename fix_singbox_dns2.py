path = "app/src/main/java/app/slipnet/tunnel/SingBoxBridge.kt"

with open(path, "r") as f:
    lines = f.readlines()

# پیدا کردن شروع/پایان buildConfig
start = None
end = None
for i, line in enumerate(lines):
    if "private fun buildConfig(profile: ServerProfile" in line:
        start = i
        break

if start is None:
    raise SystemExit("buildConfig not found")

brace = 0
for i in range(start, len(lines)):
    brace += lines[i].count("{") - lines[i].count("}")
    if brace == 0 and i > start:
        end = i
        break

print(f"Replacing buildConfig lines {start+1} to {end+1}")

new_block = '''    // ==================== Config ====================

    private fun buildConfig(profile: ServerProfile, listenPort: Int, listenHost: String): String {
        val config = JSONObject()

        // لاگ
        config.put("log", JSONObject().apply {
            put("level", if (debugLogging) "debug" else "info")
            put("timestamp", true)
        })

        // DNS داخلی: DoH روی گوگل از داخل تونل
        config.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "remote")
                    put("address", "https://dns.google/dns-query")
                    put("detour", "proxy")
                })
                put(JSONObject().apply {
                    put("tag", "local")
                    put("address", "local")
                    put("detour", "direct")
                })
            })
            put("final", "remote")
            put("independent_cache", true)
            put("strategy", "prefer_ipv4")
        })

        // فقط یک inbound به صورت SOCKS5 روی لوکال
        config.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "socks")
                put("tag", "socks-in")
                put("listen", listenHost)
                put("listen_port", listenPort)
                put("sniff", true)
            })
        })

        // outbound اصلی بر اساس نوع تونل
        val outbound = when (profile.tunnelType) {
            TunnelType.VLESS -> buildVlessOutbound(profile)
            TunnelType.TROJAN -> buildTrojanOutbound(profile)
            TunnelType.HYSTERIA2 -> buildHysteria2Outbound(profile)
            TunnelType.SHADOWSOCKS -> buildShadowsocksOutbound(profile)
            else -> throw IllegalArgumentException("Unsupported: ${profile.tunnelType}")
        }

        // outbound ها: proxy اصلی، direct، و dns-out
        config.put("outbounds", JSONArray().apply {
            put(outbound)
            put(JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
            })
            put(JSONObject().apply {
                put("type", "dns")
                put("tag", "dns-out")
            })
        })

        // تمام ترافیک DNS (TCP/UDP) بره به dns-out، بقیه به proxy
        config.put("route", JSONObject().apply {
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("protocol", "dns")
                    put("outbound", "dns-out")
                })
            })
            put("final", "proxy")
        })

        return config.toString(2)
    }

'''

lines[start:end+1] = [new_block]

with open(path, "w") as f:
    f.writelines(lines)

print("SingBoxBridge.buildConfig (with DNS) replaced!")
