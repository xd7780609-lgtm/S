package app.slipnet.domain.model

data class ServerProfile(
    val id: Long = 0,
    val name: String,
    val domain: String = "",
    val resolvers: List<DnsResolver> = emptyList(),
    val authoritativeMode: Boolean = false,
    val keepAliveInterval: Int = 200,
    val congestionControl: CongestionControl = CongestionControl.BBR,
    val gsoEnabled: Boolean = false,
    val tcpListenPort: Int = 1080,
    val tcpListenHost: String = "127.0.0.1",
    val socksUsername: String? = null,
    val socksPassword: String? = null,
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val tunnelType: TunnelType = TunnelType.DNSTT,
    val dnsttPublicKey: String = "",
    val sshUsername: String = "",
    val sshPassword: String = "",
    val sshPort: Int = 22,
    val sshHost: String = "127.0.0.1",
    val dohUrl: String = "",
    val lastConnectedAt: Long = 0,
    val dnsTransport: DnsTransport = DnsTransport.UDP,
    val sshAuthType: SshAuthType = SshAuthType.PASSWORD,
    val sshPrivateKey: String = "",
    val sshKeyPassphrase: String = "",
    val torBridgeLines: String = "",
    val sortOrder: Int = 0,
    val dnsttAuthoritative: Boolean = false,
    val naivePort: Int = 443,
    val naiveUsername: String = "",
    val naivePassword: String = "",

    // ===== VLESS fields =====
    val vlessUuid: String = "",
    val vlessAddress: String = "",
    val vlessPort: Int = 443,
    val vlessSecurity: String = "tls",
    val vlessFlow: String = "",
    val vlessSni: String = "",
    val vlessFingerprint: String = "chrome",
    val vlessNetwork: String = "tcp",
    val vlessWsPath: String = "",
    val vlessWsHost: String = "",
    val vlessGrpcServiceName: String = "",
    val vlessRealityPublicKey: String = "",
    val vlessRealityShortId: String = "",

    // ===== Trojan fields =====
    val trojanPassword: String = "",
    val trojanAddress: String = "",
    val trojanPort: Int = 443,
    val trojanSni: String = "",
    val trojanFingerprint: String = "chrome",
    val trojanNetwork: String = "tcp",
    val trojanWsPath: String = "",
    val trojanWsHost: String = "",
    val trojanGrpcServiceName: String = "",
    val trojanAllowInsecure: Boolean = false,

    // ===== Hysteria2 fields =====
    val hy2Password: String = "",
    val hy2Address: String = "",
    val hy2Port: Int = 443,
    val hy2Sni: String = "",
    val hy2AllowInsecure: Boolean = false,
    val hy2UpMbps: Int = 100,
    val hy2DownMbps: Int = 100,
    val hy2Obfs: String = "",
    val hy2ObfsPassword: String = "",

    // ===== Shadowsocks fields =====
    val ssAddress: String = "",
    val ssPort: Int = 8388,
    val ssPassword: String = "",
    val ssMethod: String = "2022-blake3-aes-128-gcm",

    // ===== Common proxy fields =====
    val proxyFragment: Boolean = false,
    val fragmentSize: String = "10-100",
    val fragmentInterval: String = "10-50",
    val fragmentHost: String = "",
    val proxyMux: Boolean = false,
    val muxProtocol: String = "h2mux",
    val muxMaxConnections: Int = 4,

    // ===== CDN Scanner fields =====
    val isScannerProfile: Boolean = false,
    val lastScannedIp: String = "",
    val lastScanTime: Long = 0
)

data class DnsResolver(
    val host: String,
    val port: Int = 53,
    val authoritative: Boolean = false
)

enum class CongestionControl(val value: String) {
    BBR("bbr"),
    DCUBIC("dcubic");

    companion object {
        fun fromValue(value: String): CongestionControl {
            return entries.find { it.value == value } ?: BBR
        }
    }
}

enum class TunnelType(val value: String, val displayName: String) {
    SLIPSTREAM("slipstream", "Slipstream"),
    SLIPSTREAM_SSH("slipstream_ssh", "Slipstream + SSH"),
    DNSTT("dnstt", "DNSTT"),
    DNSTT_SSH("dnstt_ssh", "DNSTT + SSH"),
    SSH("ssh", "SSH"),
    DOH("doh", "DOH (DNS over HTTPS)"),
    SNOWFLAKE("snowflake", "Tor"),
    NAIVE_SSH("naive_ssh", "SlipGate"),
    VLESS("vless", "VLESS"),
    TROJAN("trojan", "Trojan"),
    HYSTERIA2("hysteria2", "Hysteria2"),
    SHADOWSOCKS("shadowsocks", "Shadowsocks");

    companion object {
        fun fromValue(value: String): TunnelType {
            return entries.find { it.value == value } ?: DNSTT
        }
    }
}

enum class SshAuthType(val value: String) {
    PASSWORD("password"),
    KEY("key");

    companion object {
        fun fromValue(value: String): SshAuthType {
            return entries.find { it.value == value } ?: PASSWORD
        }
    }
}

enum class DnsTransport(val value: String, val displayName: String) {
    UDP("udp", "UDP"),
    DOT("dot", "DoT"),
    DOH("doh", "DoH");

    companion object {
        fun fromValue(value: String): DnsTransport {
            return entries.find { it.value == value } ?: UDP
        }
    }
}