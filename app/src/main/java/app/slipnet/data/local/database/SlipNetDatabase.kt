package app.slipnet.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProfileEntity::class],
    version = 18,
    exportSchema = true
)
abstract class SlipNetDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    companion object {
        const val DATABASE_NAME = "slipstream_database"

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN ssh_port INTEGER NOT NULL DEFAULT 22")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN forward_dns_through_ssh INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN ssh_host TEXT NOT NULL DEFAULT '127.0.0.1'")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // use_server_dns column removed — no-op migration to preserve chain
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN doh_url TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN last_connected_at INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN dns_transport TEXT NOT NULL DEFAULT 'udp'")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN ssh_auth_type TEXT NOT NULL DEFAULT 'password'")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN ssh_private_key TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN ssh_key_passphrase TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN tor_bridge_lines TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    UPDATE server_profiles SET sort_order = (
                        SELECT COUNT(*) FROM server_profiles AS sp2
                        WHERE sp2.updated_at > server_profiles.updated_at
                           OR (sp2.updated_at = server_profiles.updated_at AND sp2.id < server_profiles.id)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val cursor = db.query("PRAGMA table_info(server_profiles)")
                var hasUseServerDns = false
                while (cursor.moveToNext()) {
                    val nameIdx = cursor.getColumnIndex("name")
                    if (nameIdx >= 0 && cursor.getString(nameIdx) == "use_server_dns") {
                        hasUseServerDns = true
                        break
                    }
                }
                cursor.close()

                if (hasUseServerDns) {
                    db.execSQL("""
                        CREATE TABLE server_profiles_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            domain TEXT NOT NULL,
                            resolvers_json TEXT NOT NULL,
                            authoritative_mode INTEGER NOT NULL,
                            keep_alive_interval INTEGER NOT NULL,
                            congestion_control TEXT NOT NULL,
                            gso_enabled INTEGER NOT NULL,
                            tcp_listen_port INTEGER NOT NULL,
                            tcp_listen_host TEXT NOT NULL,
                            socks_username TEXT NOT NULL DEFAULT '',
                            socks_password TEXT NOT NULL DEFAULT '',
                            is_active INTEGER NOT NULL,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            tunnel_type TEXT NOT NULL DEFAULT 'slipstream',
                            dnstt_public_key TEXT NOT NULL DEFAULT '',
                            ssh_enabled INTEGER NOT NULL DEFAULT 0,
                            ssh_username TEXT NOT NULL DEFAULT '',
                            ssh_password TEXT NOT NULL DEFAULT '',
                            ssh_port INTEGER NOT NULL DEFAULT 22,
                            forward_dns_through_ssh INTEGER NOT NULL DEFAULT 0,
                            ssh_host TEXT NOT NULL DEFAULT '127.0.0.1',
                            doh_url TEXT NOT NULL DEFAULT '',
                            last_connected_at INTEGER NOT NULL DEFAULT 0,
                            dns_transport TEXT NOT NULL DEFAULT 'udp',
                            ssh_auth_type TEXT NOT NULL DEFAULT 'password',
                            ssh_private_key TEXT NOT NULL DEFAULT '',
                            ssh_key_passphrase TEXT NOT NULL DEFAULT '',
                            tor_bridge_lines TEXT NOT NULL DEFAULT '',
                            sort_order INTEGER NOT NULL DEFAULT 0,
                            dnstt_authoritative INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    db.execSQL("""
                        INSERT INTO server_profiles_new (
                            id, name, domain, resolvers_json, authoritative_mode,
                            keep_alive_interval, congestion_control, gso_enabled,
                            tcp_listen_port, tcp_listen_host, socks_username, socks_password,
                            is_active, created_at, updated_at, tunnel_type, dnstt_public_key,
                            ssh_enabled, ssh_username, ssh_password, ssh_port,
                            forward_dns_through_ssh, ssh_host, doh_url, last_connected_at,
                            dns_transport, ssh_auth_type, ssh_private_key, ssh_key_passphrase,
                            tor_bridge_lines, sort_order
                        )
                        SELECT
                            id, name, domain, resolvers_json, authoritative_mode,
                            keep_alive_interval, congestion_control, gso_enabled,
                            tcp_listen_port, tcp_listen_host, socks_username, socks_password,
                            is_active, created_at, updated_at, tunnel_type, dnstt_public_key,
                            ssh_enabled, ssh_username, ssh_password, ssh_port,
                            forward_dns_through_ssh, ssh_host, doh_url, last_connected_at,
                            dns_transport, ssh_auth_type, ssh_private_key, ssh_key_passphrase,
                            tor_bridge_lines, sort_order
                        FROM server_profiles
                    """.trimIndent())
                    db.execSQL("DROP TABLE server_profiles")
                    db.execSQL("ALTER TABLE server_profiles_new RENAME TO server_profiles")
                } else {
                    db.execSQL("ALTER TABLE server_profiles ADD COLUMN dnstt_authoritative INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val cursor = db.query("PRAGMA table_info(server_profiles)")
                var hasUseServerDns = false
                while (cursor.moveToNext()) {
                    val nameIdx = cursor.getColumnIndex("name")
                    if (nameIdx >= 0 && cursor.getString(nameIdx) == "use_server_dns") {
                        hasUseServerDns = true
                        break
                    }
                }
                cursor.close()

                if (hasUseServerDns) {
                    db.execSQL("""
                        CREATE TABLE server_profiles_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            domain TEXT NOT NULL,
                            resolvers_json TEXT NOT NULL,
                            authoritative_mode INTEGER NOT NULL,
                            keep_alive_interval INTEGER NOT NULL,
                            congestion_control TEXT NOT NULL,
                            gso_enabled INTEGER NOT NULL,
                            tcp_listen_port INTEGER NOT NULL,
                            tcp_listen_host TEXT NOT NULL,
                            socks_username TEXT NOT NULL DEFAULT '',
                            socks_password TEXT NOT NULL DEFAULT '',
                            is_active INTEGER NOT NULL,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            tunnel_type TEXT NOT NULL DEFAULT 'slipstream',
                            dnstt_public_key TEXT NOT NULL DEFAULT '',
                            ssh_enabled INTEGER NOT NULL DEFAULT 0,
                            ssh_username TEXT NOT NULL DEFAULT '',
                            ssh_password TEXT NOT NULL DEFAULT '',
                            ssh_port INTEGER NOT NULL DEFAULT 22,
                            forward_dns_through_ssh INTEGER NOT NULL DEFAULT 0,
                            ssh_host TEXT NOT NULL DEFAULT '127.0.0.1',
                            doh_url TEXT NOT NULL DEFAULT '',
                            last_connected_at INTEGER NOT NULL DEFAULT 0,
                            dns_transport TEXT NOT NULL DEFAULT 'udp',
                            ssh_auth_type TEXT NOT NULL DEFAULT 'password',
                            ssh_private_key TEXT NOT NULL DEFAULT '',
                            ssh_key_passphrase TEXT NOT NULL DEFAULT '',
                            tor_bridge_lines TEXT NOT NULL DEFAULT '',
                            sort_order INTEGER NOT NULL DEFAULT 0,
                            dnstt_authoritative INTEGER NOT NULL DEFAULT 0,
                            naive_port INTEGER NOT NULL DEFAULT 443,
                            naive_username TEXT NOT NULL DEFAULT '',
                            naive_password TEXT NOT NULL DEFAULT ''
                        )
                    """.trimIndent())
                    db.execSQL("""
                        INSERT INTO server_profiles_new (
                            id, name, domain, resolvers_json, authoritative_mode,
                            keep_alive_interval, congestion_control, gso_enabled,
                            tcp_listen_port, tcp_listen_host, socks_username, socks_password,
                            is_active, created_at, updated_at, tunnel_type, dnstt_public_key,
                            ssh_enabled, ssh_username, ssh_password, ssh_port,
                            forward_dns_through_ssh, ssh_host, doh_url, last_connected_at,
                            dns_transport, ssh_auth_type, ssh_private_key, ssh_key_passphrase,
                            tor_bridge_lines, sort_order, dnstt_authoritative
                        )
                        SELECT
                            id, name, domain, resolvers_json, authoritative_mode,
                            keep_alive_interval, congestion_control, gso_enabled,
                            tcp_listen_port, tcp_listen_host, socks_username, socks_password,
                            is_active, created_at, updated_at, tunnel_type, dnstt_public_key,
                            ssh_enabled, ssh_username, ssh_password, ssh_port,
                            forward_dns_through_ssh, ssh_host, doh_url, last_connected_at,
                            dns_transport, ssh_auth_type, ssh_private_key, ssh_key_passphrase,
                            tor_bridge_lines, sort_order, dnstt_authoritative
                        FROM server_profiles
                    """.trimIndent())
                    db.execSQL("DROP TABLE server_profiles")
                    db.execSQL("ALTER TABLE server_profiles_new RENAME TO server_profiles")
                } else {
                    db.execSQL("ALTER TABLE server_profiles ADD COLUMN naive_port INTEGER NOT NULL DEFAULT 443")
                    db.execSQL("ALTER TABLE server_profiles ADD COLUMN naive_username TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE server_profiles ADD COLUMN naive_password TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // VLESS fields
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN vless_uuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN vless_address TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN vless_port INTEGER NOT NULL DEFAULT 443")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN vless_security TEXT NOT NULL DEFAULT 'tls'")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN vless_flow TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN vless_sni TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN vless_fingerprint TEXT NOT NULL DEFAULT 'chrome'")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN vless_network TEXT NOT NULL DEFAULT 'tcp'")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN vless_ws_path TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN vless_ws_host TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN vless_grpc_service_name TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN vless_reality_public_key TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN vless_reality_short_id TEXT NOT NULL DEFAULT ''")

                // Trojan fields
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN trojan_password TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN trojan_address TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN trojan_port INTEGER NOT NULL DEFAULT 443")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN trojan_sni TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN trojan_fingerprint TEXT NOT NULL DEFAULT 'chrome'")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN trojan_network TEXT NOT NULL DEFAULT 'tcp'")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN trojan_ws_path TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN trojan_ws_host TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN trojan_grpc_service_name TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN trojan_allow_insecure INTEGER NOT NULL DEFAULT 0")

                // Hysteria2 fields
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN hy2_password TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN hy2_address TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN hy2_port INTEGER NOT NULL DEFAULT 443")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN hy2_sni TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN hy2_allow_insecure INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN hy2_up_mbps INTEGER NOT NULL DEFAULT 100")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN hy2_down_mbps INTEGER NOT NULL DEFAULT 100")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN hy2_obfs TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN hy2_obfs_password TEXT NOT NULL DEFAULT ''")

                // Shadowsocks fields
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN ss_address TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN ss_port INTEGER NOT NULL DEFAULT 8388")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN ss_password TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN ss_method TEXT NOT NULL DEFAULT '2022-blake3-aes-128-gcm'")

                // Common proxy fields
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN proxy_fragment INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN fragment_size TEXT NOT NULL DEFAULT '10-100'")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN fragment_interval TEXT NOT NULL DEFAULT '10-50'")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN fragment_host TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN proxy_mux INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN mux_protocol TEXT NOT NULL DEFAULT 'h2mux'")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN mux_max_connections INTEGER NOT NULL DEFAULT 4")

                // CDN Scanner fields
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN is_scanner_profile INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN last_scanned_ip TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN last_scan_time INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}