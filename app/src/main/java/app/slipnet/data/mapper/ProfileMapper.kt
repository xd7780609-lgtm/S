package app.slipnet.data.mapper

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import app.slipnet.data.local.database.ProfileEntity
import app.slipnet.domain.model.CongestionControl
import app.slipnet.domain.model.DnsResolver
import app.slipnet.domain.model.DnsTransport
import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.model.SshAuthType
import app.slipnet.domain.model.TunnelType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileMapper @Inject constructor(
    private val gson: Gson
) {
    fun toDomain(entity: ProfileEntity): ServerProfile {
        val resolversType = object : TypeToken<List<DnsResolver>>() {}.type
        val resolvers: List<DnsResolver> = try {
            gson.fromJson(entity.resolversJson, resolversType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        return ServerProfile(
            id = entity.id,
            name = entity.name,
            domain = entity.domain,
            resolvers = resolvers,
            authoritativeMode = entity.authoritativeMode,
            keepAliveInterval = entity.keepAliveInterval,
            congestionControl = CongestionControl.fromValue(entity.congestionControl),
            gsoEnabled = entity.gsoEnabled,
            tcpListenPort = entity.tcpListenPort,
            tcpListenHost = entity.tcpListenHost,
            socksUsername = entity.socksUsername.ifBlank { null },
            socksPassword = entity.socksPassword.ifBlank { null },
            isActive = entity.isActive,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            tunnelType = TunnelType.fromValue(entity.tunnelType),
            dnsttPublicKey = entity.dnsttPublicKey,
            sshUsername = entity.sshUsername,
            sshPassword = entity.sshPassword,
            sshPort = entity.sshPort,
            sshHost = entity.sshHost,
            dohUrl = entity.dohUrl,
            lastConnectedAt = entity.lastConnectedAt,
            dnsTransport = DnsTransport.fromValue(entity.dnsTransport),
            sshAuthType = SshAuthType.fromValue(entity.sshAuthType),
            sshPrivateKey = entity.sshPrivateKey,
            sshKeyPassphrase = entity.sshKeyPassphrase,
            torBridgeLines = entity.torBridgeLines,
            sortOrder = entity.sortOrder,
            dnsttAuthoritative = entity.dnsttAuthoritative,
            naivePort = entity.naivePort,
            naiveUsername = entity.naiveUsername,
            naivePassword = entity.naivePassword,
            // VLESS
            vlessUuid = entity.vlessUuid,
            vlessAddress = entity.vlessAddress,
            vlessPort = entity.vlessPort,
            vlessSecurity = entity.vlessSecurity,
            vlessFlow = entity.vlessFlow,
            vlessSni = entity.vlessSni,
            vlessFingerprint = entity.vlessFingerprint,
            vlessNetwork = entity.vlessNetwork,
            vlessWsPath = entity.vlessWsPath,
            vlessWsHost = entity.vlessWsHost,
            vlessGrpcServiceName = entity.vlessGrpcServiceName,
            vlessRealityPublicKey = entity.vlessRealityPublicKey,
            vlessRealityShortId = entity.vlessRealityShortId,
            // Trojan
            trojanPassword = entity.trojanPassword,
            trojanAddress = entity.trojanAddress,
            trojanPort = entity.trojanPort,
            trojanSni = entity.trojanSni,
            trojanFingerprint = entity.trojanFingerprint,
            trojanNetwork = entity.trojanNetwork,
            trojanWsPath = entity.trojanWsPath,
            trojanWsHost = entity.trojanWsHost,
            trojanGrpcServiceName = entity.trojanGrpcServiceName,
            trojanAllowInsecure = entity.trojanAllowInsecure,
            // Hysteria2
            hy2Password = entity.hy2Password,
            hy2Address = entity.hy2Address,
            hy2Port = entity.hy2Port,
            hy2Sni = entity.hy2Sni,
            hy2AllowInsecure = entity.hy2AllowInsecure,
            hy2UpMbps = entity.hy2UpMbps,
            hy2DownMbps = entity.hy2DownMbps,
            hy2Obfs = entity.hy2Obfs,
            hy2ObfsPassword = entity.hy2ObfsPassword,
            // Shadowsocks
            ssAddress = entity.ssAddress,
            ssPort = entity.ssPort,
            ssPassword = entity.ssPassword,
            ssMethod = entity.ssMethod,
            // Common proxy
            proxyFragment = entity.proxyFragment,
            fragmentSize = entity.fragmentSize,
            fragmentInterval = entity.fragmentInterval,
            fragmentHost = entity.fragmentHost,
            proxyMux = entity.proxyMux,
            muxProtocol = entity.muxProtocol,
            muxMaxConnections = entity.muxMaxConnections,
            // Scanner
            isScannerProfile = entity.isScannerProfile,
            lastScannedIp = entity.lastScannedIp,
            lastScanTime = entity.lastScanTime
        )
    }

    fun toEntity(profile: ServerProfile): ProfileEntity {
        val resolversJson = gson.toJson(profile.resolvers)

        return ProfileEntity(
            id = profile.id,
            name = profile.name,
            domain = profile.domain,
            resolversJson = resolversJson,
            authoritativeMode = profile.authoritativeMode,
            keepAliveInterval = profile.keepAliveInterval,
            congestionControl = profile.congestionControl.value,
            gsoEnabled = profile.gsoEnabled,
            tcpListenPort = profile.tcpListenPort,
            tcpListenHost = profile.tcpListenHost,
            socksUsername = profile.socksUsername ?: "",
            socksPassword = profile.socksPassword ?: "",
            isActive = profile.isActive,
            createdAt = profile.createdAt,
            updatedAt = profile.updatedAt,
            tunnelType = profile.tunnelType.value,
            dnsttPublicKey = profile.dnsttPublicKey,
            sshEnabled = profile.tunnelType == TunnelType.SSH ||
                    profile.tunnelType == TunnelType.DNSTT_SSH ||
                    profile.tunnelType == TunnelType.SLIPSTREAM_SSH ||
                    profile.tunnelType == TunnelType.NAIVE_SSH,
            sshUsername = profile.sshUsername,
            sshPassword = profile.sshPassword,
            sshPort = profile.sshPort,
            forwardDnsThroughSsh = false,
            sshHost = profile.sshHost,
            dohUrl = profile.dohUrl,
            lastConnectedAt = profile.lastConnectedAt,
            dnsTransport = profile.dnsTransport.value,
            sshAuthType = profile.sshAuthType.value,
            sshPrivateKey = profile.sshPrivateKey,
            sshKeyPassphrase = profile.sshKeyPassphrase,
            torBridgeLines = profile.torBridgeLines,
            sortOrder = profile.sortOrder,
            dnsttAuthoritative = profile.dnsttAuthoritative,
            naivePort = profile.naivePort,
            naiveUsername = profile.naiveUsername,
            naivePassword = profile.naivePassword,
            // VLESS
            vlessUuid = profile.vlessUuid,
            vlessAddress = profile.vlessAddress,
            vlessPort = profile.vlessPort,
            vlessSecurity = profile.vlessSecurity,
            vlessFlow = profile.vlessFlow,
            vlessSni = profile.vlessSni,
            vlessFingerprint = profile.vlessFingerprint,
            vlessNetwork = profile.vlessNetwork,
            vlessWsPath = profile.vlessWsPath,
            vlessWsHost = profile.vlessWsHost,
            vlessGrpcServiceName = profile.vlessGrpcServiceName,
            vlessRealityPublicKey = profile.vlessRealityPublicKey,
            vlessRealityShortId = profile.vlessRealityShortId,
            // Trojan
            trojanPassword = profile.trojanPassword,
            trojanAddress = profile.trojanAddress,
            trojanPort = profile.trojanPort,
            trojanSni = profile.trojanSni,
            trojanFingerprint = profile.trojanFingerprint,
            trojanNetwork = profile.trojanNetwork,
            trojanWsPath = profile.trojanWsPath,
            trojanWsHost = profile.trojanWsHost,
            trojanGrpcServiceName = profile.trojanGrpcServiceName,
            trojanAllowInsecure = profile.trojanAllowInsecure,
            // Hysteria2
            hy2Password = profile.hy2Password,
            hy2Address = profile.hy2Address,
            hy2Port = profile.hy2Port,
            hy2Sni = profile.hy2Sni,
            hy2AllowInsecure = profile.hy2AllowInsecure,
            hy2UpMbps = profile.hy2UpMbps,
            hy2DownMbps = profile.hy2DownMbps,
            hy2Obfs = profile.hy2Obfs,
            hy2ObfsPassword = profile.hy2ObfsPassword,
            // Shadowsocks
            ssAddress = profile.ssAddress,
            ssPort = profile.ssPort,
            ssPassword = profile.ssPassword,
            ssMethod = profile.ssMethod,
            // Common proxy
            proxyFragment = profile.proxyFragment,
            fragmentSize = profile.fragmentSize,
            fragmentInterval = profile.fragmentInterval,
            fragmentHost = profile.fragmentHost,
            proxyMux = profile.proxyMux,
            muxProtocol = profile.muxProtocol,
            muxMaxConnections = profile.muxMaxConnections,
            // Scanner
            isScannerProfile = profile.isScannerProfile,
            lastScannedIp = profile.lastScannedIp,
            lastScanTime = profile.lastScanTime
        )
    }

    fun toDomainList(entities: List<ProfileEntity>): List<ServerProfile> {
        return entities.map { toDomain(it) }
    }
}