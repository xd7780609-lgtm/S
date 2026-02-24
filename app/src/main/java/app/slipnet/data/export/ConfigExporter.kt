package app.slipnet.data.export

import android.util.Base64
import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.model.SshAuthType
import app.slipnet.domain.model.TunnelType
import javax.inject.Inject
import javax.inject.Singleton
import java.net.URLEncoder


/**
 * Exports profiles to compact encoded text format.
 *
 * Single profile format: slipnet://[base64-encoded-profile]
 * Multiple profiles: one URI per line
 *
 * Encoded profile format v14 (pipe-delimited):
 * v14|tunnelType|name|domain|resolvers|authMode|keepAlive|cc|port|host|gso|dnsttPublicKey|socksUsername|socksPassword|sshEnabled|sshUsername|sshPassword|sshPort|forwardDnsThroughSsh|sshHost|useServerDns|dohUrl|dnsTransport|sshAuthType|sshPrivateKey(b64)|sshKeyPassphrase(b64)|torBridgeLines(b64)|dnsttAuthoritative|naivePort|naiveUsername|naivePassword(b64)
 *
 * Resolvers format (comma-separated): host:port:auth,host:port:auth
 */
@Singleton
class ConfigExporter @Inject constructor() {

    companion object {
        const val SCHEME = "slipnet://"
        const val VERSION = "14"
        const val MODE_SLIPSTREAM = "ss"
        const val MODE_SLIPSTREAM_SSH = "slipstream_ssh"
        const val MODE_DNSTT = "dnstt"
        const val MODE_DNSTT_SSH = "dnstt_ssh"
        const val MODE_SSH = "ssh"
        const val MODE_DOH = "doh"
        const val MODE_SNOWFLAKE = "snowflake"
        const val MODE_NAIVE_SSH = "naive_ssh"
        const val MODE_VLESS = "vless"
        const val MODE_TROJAN = "trojan"
        const val MODE_HYSTERIA2 = "hysteria2"
        const val MODE_SHADOWSOCKS = "shadowsocks"
        private const val FIELD_DELIMITER = "|"
        private const val RESOLVER_DELIMITER = ","
        private const val RESOLVER_PART_DELIMITER = ":"
    }

    fun exportSingleProfile(profile: ServerProfile): String {
        return when (profile.tunnelType) {
            TunnelType.VLESS -> exportVlessUri(profile)
            TunnelType.TROJAN -> exportTrojanUri(profile)
            TunnelType.HYSTERIA2 -> exportHy2Uri(profile)
            TunnelType.SHADOWSOCKS -> exportSsUri(profile)
            else -> encodeProfile(profile)
        }
    }

    fun exportAllProfiles(profiles: List<ServerProfile>): String {
        return profiles.joinToString("\n") { exportSingleProfile(it) }
    }

    private fun encodeProfile(profile: ServerProfile): String {
        val resolversStr = profile.resolvers.joinToString(RESOLVER_DELIMITER) { resolver ->
            "${resolver.host}${RESOLVER_PART_DELIMITER}${resolver.port}${RESOLVER_PART_DELIMITER}${if (resolver.authoritative) "1" else "0"}"
        }

        val tunnelTypeStr = when (profile.tunnelType) {
            TunnelType.SLIPSTREAM -> MODE_SLIPSTREAM
            TunnelType.SLIPSTREAM_SSH -> MODE_SLIPSTREAM_SSH
            TunnelType.DNSTT -> MODE_DNSTT
            TunnelType.DNSTT_SSH -> MODE_DNSTT_SSH
            TunnelType.SSH -> MODE_SSH
            TunnelType.DOH -> MODE_DOH
            TunnelType.SNOWFLAKE -> MODE_SNOWFLAKE
            TunnelType.NAIVE_SSH -> MODE_NAIVE_SSH
            TunnelType.VLESS -> MODE_VLESS
            TunnelType.TROJAN -> MODE_TROJAN
            TunnelType.HYSTERIA2 -> MODE_HYSTERIA2
            TunnelType.SHADOWSOCKS -> MODE_SHADOWSOCKS
        }

        val data = listOf(
            VERSION,
            tunnelTypeStr,
            profile.name,
            profile.domain,
            resolversStr,
            if (profile.authoritativeMode) "1" else "0",
            profile.keepAliveInterval.toString(),
            profile.congestionControl.value,
            profile.tcpListenPort.toString(),
            profile.tcpListenHost,
            if (profile.gsoEnabled) "1" else "0",
            profile.dnsttPublicKey,
            profile.socksUsername ?: "",
            profile.socksPassword ?: "",
            if (profile.tunnelType == TunnelType.SSH || profile.tunnelType == TunnelType.DNSTT_SSH || profile.tunnelType == TunnelType.SLIPSTREAM_SSH || profile.tunnelType == TunnelType.NAIVE_SSH) "1" else "0",
            profile.sshUsername,
            profile.sshPassword,
            profile.sshPort.toString(),
            "0",
            profile.sshHost,
            "0",
            profile.dohUrl,
            profile.dnsTransport.value,
            profile.sshAuthType.value,
            Base64.encodeToString(profile.sshPrivateKey.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
            Base64.encodeToString(profile.sshKeyPassphrase.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
            Base64.encodeToString(profile.torBridgeLines.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
            if (profile.dnsttAuthoritative) "1" else "0",
            profile.naivePort.toString(),
            profile.naiveUsername,
            Base64.encodeToString(profile.naivePassword.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        ).joinToString(FIELD_DELIMITER)

        val encoded = Base64.encodeToString(data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "$SCHEME$encoded"
    }

    // ========== URI Export Functions ==========

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private fun exportVlessUri(p: ServerProfile): String {
        val host = p.vlessAddress.ifBlank { p.domain }
        val port = p.vlessPort
        val params = linkedMapOf<String, String>()

        if (p.vlessNetwork.isNotBlank() && p.vlessNetwork != "tcp") params["type"] = p.vlessNetwork
        if (p.vlessSecurity.isNotBlank()) params["security"] = p.vlessSecurity
        if (p.vlessFlow.isNotBlank()) params["flow"] = p.vlessFlow
        if (p.vlessSni.isNotBlank()) params["sni"] = p.vlessSni
        if (p.vlessFingerprint.isNotBlank()) params["fp"] = p.vlessFingerprint
        if (p.vlessWsPath.isNotBlank()) params["path"] = p.vlessWsPath
        if (p.vlessWsHost.isNotBlank()) params["host"] = p.vlessWsHost
        if (p.vlessGrpcServiceName.isNotBlank()) params["serviceName"] = p.vlessGrpcServiceName
        if (p.vlessRealityPublicKey.isNotBlank()) params["pbk"] = p.vlessRealityPublicKey
        if (p.vlessRealityShortId.isNotBlank()) params["sid"] = p.vlessRealityShortId

        val query = if (params.isEmpty()) "" else params.entries.joinToString("&", prefix = "?") { "${it.key}=${enc(it.value)}" }
        return "vless://${p.vlessUuid}@${host}:${port}${query}#${enc(p.name)}"
    }

    private fun exportTrojanUri(p: ServerProfile): String {
        val host = p.trojanAddress.ifBlank { p.domain }
        val port = p.trojanPort
        val params = linkedMapOf<String, String>()

        if (p.trojanSni.isNotBlank()) params["sni"] = p.trojanSni
        if (p.trojanFingerprint.isNotBlank()) params["fp"] = p.trojanFingerprint
        if (p.trojanNetwork.isNotBlank() && p.trojanNetwork != "tcp") params["type"] = p.trojanNetwork
        if (p.trojanWsPath.isNotBlank()) params["path"] = p.trojanWsPath
        if (p.trojanWsHost.isNotBlank()) params["host"] = p.trojanWsHost
        if (p.trojanGrpcServiceName.isNotBlank()) params["serviceName"] = p.trojanGrpcServiceName
        if (p.trojanAllowInsecure) params["allowInsecure"] = "1"

        val query = if (params.isEmpty()) "" else params.entries.joinToString("&", prefix = "?") { "${it.key}=${enc(it.value)}" }
        return "trojan://${enc(p.trojanPassword)}@${host}:${port}${query}#${enc(p.name)}"
    }

    private fun exportHy2Uri(p: ServerProfile): String {
        val host = p.hy2Address.ifBlank { p.domain }
        val port = p.hy2Port
        val params = linkedMapOf<String, String>()

        if (p.hy2Sni.isNotBlank()) params["sni"] = p.hy2Sni
        if (p.hy2AllowInsecure) params["insecure"] = "1"
        if (p.hy2Obfs.isNotBlank()) params["obfs"] = p.hy2Obfs
        if (p.hy2ObfsPassword.isNotBlank()) params["obfs-password"] = p.hy2ObfsPassword

        val query = if (params.isEmpty()) "" else params.entries.joinToString("&", prefix = "?") { "${it.key}=${enc(it.value)}" }
        return "hy2://${enc(p.hy2Password)}@${host}:${port}${query}#${enc(p.name)}"
    }

    private fun exportSsUri(p: ServerProfile): String {
        val host = p.ssAddress.ifBlank { p.domain }
        val port = p.ssPort
        val userInfo = "${p.ssMethod}:${p.ssPassword}"
        val b64 = android.util.Base64.encodeToString(userInfo.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        return "ss://${b64}@${host}:${port}#${enc(p.name)}"
    }
}
