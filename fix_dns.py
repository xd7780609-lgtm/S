path = "app/src/main/java/app/slipnet/tunnel/SingBoxBridge.kt"
with open(path, "r") as f:
    content = f.read()

# 1. Remove sniff_override_destination (causes circular DNS issues)
content = content.replace(
    """                put("sniff", true)
                put("sniff_override_destination", true)""",
    '                put("sniff", true)'
)

# 2. Add port 53 route rule to catch ALL DNS traffic
content = content.replace(
    """                put(JSONObject().apply {
                    put("protocol", "dns")
                    put("outbound", "dns-out")
                })
            })
            put("final", "proxy")""",
    """                put(JSONObject().apply {
                    put("protocol", "dns")
                    put("outbound", "dns-out")
                })
                put(JSONObject().apply {
                    put("port", 53)
                    put("outbound", "dns-out")
                })
            })
            put("final", "proxy")"""
)

# 3. Add DNS cache and IPv4 strategy
content = content.replace(
    """            put("final", "remote")
        })

        config.put("inbounds""",
    """            put("final", "remote")
            put("independent_cache", true)
            put("strategy", "prefer_ipv4")
        })

        config.put("inbounds"""
)

with open(path, "w") as f:
    f.write(content)

print("SingBoxBridge DNS fixed!")