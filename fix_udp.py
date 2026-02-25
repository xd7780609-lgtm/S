
# 1. Fix HevSocks5Tunnel to support udp mode parameter
hev_path = "app/src/main/java/app/slipnet/tunnel/HevSocks5Tunnel.kt"
with open(hev_path, "r") as f:
    hev = f.read()

# Add udpMode parameter to start()
hev = hev.replace(
    "        enableUdpTunneling: Boolean = false,",
    "        enableUdpTunneling: Boolean = false,\n        udpMode: String = \"tcp\","
)

# Pass udpMode to buildConfig
hev = hev.replace(
    """        val config = buildConfig(
            socksAddress = socksAddress,
            socksPort = socksPort,
            socksUsername = socksUsername,
            socksPassword = socksPassword,
            enableUdpTunneling = enableUdpTunneling,
            mtu = mtu,
            ipv4Address = ipv4Address
        )""",
    """        val config = buildConfig(
            socksAddress = socksAddress,
            socksPort = socksPort,
            socksUsername = socksUsername,
            socksPassword = socksPassword,
            enableUdpTunneling = enableUdpTunneling,
            udpMode = udpMode,
            mtu = mtu,
            ipv4Address = ipv4Address
        )"""
)

# Add udpMode to buildConfig function signature
hev = hev.replace(
    """    private fun buildConfig(
        socksAddress: String,
        socksPort: Int,
        socksUsername: String?,
        socksPassword: String?,
        enableUdpTunneling: Boolean,
        mtu: Int,
        ipv4Address: String
    ): String {""",
    """    private fun buildConfig(
        socksAddress: String,
        socksPort: Int,
        socksUsername: String?,
        socksPassword: String?,
        enableUdpTunneling: Boolean,
        udpMode: String = "tcp",
        mtu: Int,
        ipv4Address: String
    ): String {"""
)

# Change udp config line to use udpMode
hev = hev.replace(
    """        if (enableUdpTunneling) {
            sb.appendLine("  udp: 'tcp'")
        }""",
    """        if (enableUdpTunneling) {
            sb.appendLine("  udp: '$udpMode'")
        }"""
)

with open(hev_path, "w") as f:
    f.write(hev)

print("HevSocks5Tunnel updated!")

# 2. Fix VpnRepositoryImpl to pass udpMode based on tunnel type
repo_path = "app/src/main/java/app/slipnet/data/repository/VpnRepositoryImpl.kt"
with open(repo_path, "r") as f:
    repo = f.read()

# Add udpMode detection before HevSocks5Tunnel.start call
repo = repo.replace(
    """        Log.i(TAG, "========================================")

        val hevResult = HevSocks5Tunnel.start(
            tunFd = pfd,
            socksAddress = "127.0.0.1",
            socksPort = socksPort,
            socksUsername = socksUsername,
            socksPassword = socksPassword,
            enableUdpTunneling = enableUdpTunneling,
            mtu = 1500,
            ipv4Address = "10.255.255.1",
            disableQuic = disableQuic
        )""",
    """        Log.i(TAG, "========================================")

        // sing-box uses standard SOCKS5 UDP ASSOCIATE, not custom FWD_UDP
        val udpMode = when (profile.tunnelType) {
            TunnelType.VLESS, TunnelType.TROJAN, TunnelType.HYSTERIA2, TunnelType.SHADOWSOCKS -> "udp"
            else -> "tcp"
        }
        Log.i(TAG, "  UDP mode: $udpMode")

        val hevResult = HevSocks5Tunnel.start(
            tunFd = pfd,
            socksAddress = "127.0.0.1",
            socksPort = socksPort,
            socksUsername = socksUsername,
            socksPassword = socksPassword,
            enableUdpTunneling = enableUdpTunneling,
            udpMode = udpMode,
            mtu = 1500,
            ipv4Address = "10.255.255.1",
            disableQuic = disableQuic
        )"""
)

with open(repo_path, "w") as f:
    f.write(repo)

print("VpnRepositoryImpl updated!")
