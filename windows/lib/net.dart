import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:http/io_client.dart';

bool _private(String h) =>
    h == 'localhost' || RegExp(r'^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|127\.|169\.254\.)').hasMatch(h);

/// Plex's own server names (abc-def.hash.plex.direct). Some PCs don't trust the authority behind
/// these certificates (an older Windows certificate list, or antivirus that inspects secure traffic),
/// which makes every remote connection fail at the handshake even though Plex's own app works.
bool _plexDirect(String h) => h.endsWith('.plex.direct');

/// One shared kind of connection for talking to the server: keeps connections open and reused, and
/// accepts the server's self-made certificate when (and only when) the address is on your own network
/// or is one of Plex's own server names.
http.Client makeClient() {
  final c = HttpClient()
    ..connectionTimeout = const Duration(seconds: 8)
    ..idleTimeout = const Duration(seconds: 90)
    ..maxConnectionsPerHost = 16
    ..autoUncompress = true
    ..badCertificateCallback = (cert, host, port) => _private(host) || _plexDirect(host);
  return IOClient(c);
}
