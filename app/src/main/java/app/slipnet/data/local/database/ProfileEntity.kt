package app.slipnet.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "server_profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "domain")
    val domain: String = "",

    @ColumnInfo(name = "resolvers_json")
    val resolversJson: String = "[]",

    @ColumnInfo(name = "authoritative_mode")
    val authoritativeMode: Boolean = false,

    @ColumnInfo(name = "keep_alive_interval")
    val keepAliveInterval: Int = 200,

    @ColumnInfo(name = "congestion_control")
    val congestionControl: String = "bbr",

    @ColumnInfo(name = "gso_enabled")
    val gsoEnabled: Boolean = false,

    @ColumnInfo(name = "tcp_listen_port")
    val tcpListenPort: Int = 1080,

    @ColumnInfo(name = "tcp_listen_host")
    val tcpListenHost: String = "127.0.0.1",

    @ColumnInfo(name = "socks_username", defaultValue = "")
    val socksUsername: String = "",

    @ColumnInfo(name = "socks_password", defaultValue = "")
    val socksPassword: String = "",

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "tunnel_type", defaultValue = "slipstream")
    val tunnelType: String = "slipstream",

    @ColumnInfo(name = "dnstt_public_key", defaultValue = "")
    val dnsttPublicKey: String = "",

    @ColumnInfo(name = "ssh_enabled", defaultValue = "0")
    val sshEnabled: Boolean = false,

    @ColumnInfo(name = "ssh_username", defaultValue = "")
    val sshUsername: String = "",

    @ColumnInfo(name = "ssh_password", defaultValue = "")
    val sshPassword: String = "",

    @ColumnInfo(name = "ssh_port", defaultValue = "22")
    val sshPort: Int = 22,

    @ColumnInfo(name = "forward_dns_through_ssh", defaultValue = "0")
    val forwardDnsThroughSsh: Boolean = false,

    @ColumnInfo(name = "ssh_host", defaultValue = "127.0.0.1")
    val sshHost: String = "127.0.0.1",

    @ColumnInfo(name = "doh_url", defaultValue = "")
    val dohUrl: String = "",

    @ColumnInfo(name = "last_connected_at", defaultValue = "0")
    val lastConnectedAt: Long = 0,

    @ColumnInfo(name = "dns_transport", defaultValue = "udp")
    val dnsTransport: String = "udp",

    @ColumnInfo(name = "ssh_auth_type", defaultValue = "password")
    val sshAuthType: String = "password",

    @ColumnInfo(name = "ssh_private_key", defaultValue = "")
    val sshPrivateKey: String = "",

    @ColumnInfo(name = "ssh_key_passphrase", defaultValue = "")
    val sshKeyPassphrase: String = "",

    @ColumnInfo(name = "tor_bridge_lines", defaultValue = "")
    val torBridgeLines: String = "",

    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "dnstt_authoritative", defaultValue = "0")
    val dnsttAuthoritative: Boolean = false,

    @ColumnInfo(name = "naive_port", defaultValue = "443")
    val naivePort: Int = 443,

    @ColumnInfo(name = "naive_username", defaultValue = "")
    val naiveUsername: String = "",

    @ColumnInfo(name = "naive_password", defaultValue = "")
    val naivePassword: String = "",

    // ===== VLESS fields =====
    @ColumnInfo(name = "vless_uuid", defaultValue = "")
    val vlessUuid: String = "",

    @ColumnInfo(name = "vless_address", defaultValue = "")
    val vlessAddress: String = "",

    @ColumnInfo(name = "vless_port", defaultValue = "443")
    val vlessPort: Int = 443,

    @ColumnInfo(name = "vless_security", defaultValue = "tls")
    val vlessSecurity: String = "tls",

    @ColumnInfo(name = "vless_flow", defaultValue = "")
    val vlessFlow: String = "",

    @ColumnInfo(name = "vless_sni", defaultValue = "")
    val vlessSni: String = "",

    @ColumnInfo(name = "vless_fingerprint", defaultValue = "chrome")
    val vlessFingerprint: String = "chrome",

    @ColumnInfo(name = "vless_network", defaultValue = "tcp")
    val vlessNetwork: String = "tcp",

    @ColumnInfo(name = "vless_ws_path", defaultValue = "")
    val vlessWsPath: String = "",

    @ColumnInfo(name = "vless_ws_host", defaultValue = "")
    val vlessWsHost: String = "",

    @ColumnInfo(name = "vless_grpc_service_name", defaultValue = "")
    val vlessGrpcServiceName: String = "",

    @ColumnInfo(name = "vless_reality_public_key", defaultValue = "")
    val vlessRealityPublicKey: String = "",

    @ColumnInfo(name = "vless_reality_short_id", defaultValue = "")
    val vlessRealityShortId: String = "",

    // ===== Trojan fields =====
    @ColumnInfo(name = "trojan_password", defaultValue = "")
    val trojanPassword: String = "",

    @ColumnInfo(name = "trojan_address", defaultValue = "")
    val trojanAddress: String = "",

    @ColumnInfo(name = "trojan_port", defaultValue = "443")
    val trojanPort: Int = 443,

    @ColumnInfo(name = "trojan_sni", defaultValue = "")
    val trojanSni: String = "",

    @ColumnInfo(name = "trojan_fingerprint", defaultValue = "chrome")
    val trojanFingerprint: String = "chrome",

    @ColumnInfo(name = "trojan_network", defaultValue = "tcp")
    val trojanNetwork: String = "tcp",

    @ColumnInfo(name = "trojan_ws_path", defaultValue = "")
    val trojanWsPath: String = "",

    @ColumnInfo(name = "trojan_ws_host", defaultValue = "")
    val trojanWsHost: String = "",

    @ColumnInfo(name = "trojan_grpc_service_name", defaultValue = "")
    val trojanGrpcServiceName: String = "",

    @ColumnInfo(name = "trojan_allow_insecure", defaultValue = "0")
    val trojanAllowInsecure: Boolean = false,

    // ===== Hysteria2 fields =====
    @ColumnInfo(name = "hy2_password", defaultValue = "")
    val hy2Password: String = "",

    @ColumnInfo(name = "hy2_address", defaultValue = "")
    val hy2Address: String = "",

    @ColumnInfo(name = "hy2_port", defaultValue = "443")
    val hy2Port: Int = 443,

    @ColumnInfo(name = "hy2_sni", defaultValue = "")
    val hy2Sni: String = "",

    @ColumnInfo(name = "hy2_allow_insecure", defaultValue = "0")
    val hy2AllowInsecure: Boolean = false,

    @ColumnInfo(name = "hy2_up_mbps", defaultValue = "100")
    val hy2UpMbps: Int = 100,

    @ColumnInfo(name = "hy2_down_mbps", defaultValue = "100")
    val hy2DownMbps: Int = 100,

    @ColumnInfo(name = "hy2_obfs", defaultValue = "")
    val hy2Obfs: String = "",

    @ColumnInfo(name = "hy2_obfs_password", defaultValue = "")
    val hy2ObfsPassword: String = "",

    // ===== Shadowsocks fields =====
    @ColumnInfo(name = "ss_address", defaultValue = "")
    val ssAddress: String = "",

    @ColumnInfo(name = "ss_port", defaultValue = "8388")
    val ssPort: Int = 8388,

    @ColumnInfo(name = "ss_password", defaultValue = "")
    val ssPassword: String = "",

    @ColumnInfo(name = "ss_method", defaultValue = "2022-blake3-aes-128-gcm")
    val ssMethod: String = "2022-blake3-aes-128-gcm",

    // ===== Common proxy fields =====
    @ColumnInfo(name = "proxy_fragment", defaultValue = "0")
    val proxyFragment: Boolean = false,

    @ColumnInfo(name = "fragment_size", defaultValue = "10-100")
    val fragmentSize: String = "10-100",

    @ColumnInfo(name = "fragment_interval", defaultValue = "10-50")
    val fragmentInterval: String = "10-50",

    @ColumnInfo(name = "fragment_host", defaultValue = "")
    val fragmentHost: String = "",

    @ColumnInfo(name = "proxy_mux", defaultValue = "0")
    val proxyMux: Boolean = false,

    @ColumnInfo(name = "mux_protocol", defaultValue = "h2mux")
    val muxProtocol: String = "h2mux",

    @ColumnInfo(name = "mux_max_connections", defaultValue = "4")
    val muxMaxConnections: Int = 4,

    // ===== CDN Scanner fields =====
    @ColumnInfo(name = "is_scanner_profile", defaultValue = "0")
    val isScannerProfile: Boolean = false,

    @ColumnInfo(name = "last_scanned_ip", defaultValue = "")
    val lastScannedIp: String = "",

    @ColumnInfo(name = "last_scan_time", defaultValue = "0")
    val lastScanTime: Long = 0
)