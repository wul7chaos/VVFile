using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

namespace VvFileReceiver
{
    /// <summary>手机上的一台 VVFile 客户端（发送目标）。</summary>
    internal sealed class PhoneTarget
    {
        public string Host;
        public int Port;
        public string Name;
        public string Dir;
        /** 单次 TCP 连接/读写的超时；扫描时调小，省得一台一台等。 */
        public int PingTimeoutMs = 800;

        public string Authority { get { return Host + ":" + Port; } }

        public string Label
        {
            get
            {
                if (string.IsNullOrEmpty(Name)) return Authority;
                return Name + "（" + Authority + "）";
            }
        }
    }

    /// <summary>
    /// 「电脑 → 手机」的客户端：先找手机（UDP 广播 + 本网段单播兜底），
    /// 再按和手机端完全一样的协议推文件：PUT /dir 建目录、PUT /upload 写文件。
    /// </summary>
    internal static class PhoneClient
    {
        public const int DefaultPort = 7718;
        private const string Probe = "VVFILE?DISCOVER=1";

        /// <summary>找同一局域网里的手机端。找不到返回 null。</summary>
        public static PhoneTarget Discover(int port, int timeoutMs, string lastHost)
        {
            List<string> hosts = Candidates(lastHost);
            UdpClient udp = null;
            try
            {
                udp = new UdpClient();
                udp.EnableBroadcast = true;
                udp.Client.ReceiveTimeout = 400;

                foreach (string host in hosts)
                {
                    byte[] payload = Encoding.UTF8.GetBytes(Probe);
                    try
                    {
                        if (host == "255.255.255.255" || host.EndsWith(".255"))
                            udp.Send(payload, payload.Length, new IPEndPoint(IPAddress.Broadcast, port));
                        else
                            udp.Send(payload, payload.Length, new IPEndPoint(IPAddress.Parse(host), port));
                    }
                    catch
                    {
                        continue;
                    }

                    DateTime deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
                    while (DateTime.UtcNow < deadline)
                    {
                        IPEndPoint from = new IPEndPoint(IPAddress.Any, 0);
                        byte[] reply;
                        try { reply = udp.Receive(ref from); }
                        catch (SocketException) { break; }
                        PhoneTarget hit = Parse(reply, from.Address.ToString(), port);
                        if (hit != null) return hit;
                    }
                }

                // 广播被路由器拦掉时，并行扫一遍本网段（谁先回用谁）
                return SweepAndPing(port);
            }
            catch (Exception ex)
            {
                Log.Write("找手机失败：" + ex.Message);
                return null;
            }
            finally
            {
                if (udp != null) try { udp.Close(); } catch { }
            }
        }

        /// <summary>确认某个地址是不是手机端（GET /ping）。</summary>
        public static PhoneTarget Ping(PhoneTarget target)
        {
            try
            {
                string body = Request(target, "GET", "/ping", null, null, target.PingTimeoutMs);
                if (body == null || body.IndexOf("\"vvfile-phone\"", StringComparison.Ordinal) < 0) return null;
                return new PhoneTarget
                {
                    Host = target.Host,
                    Port = JsonNumber(body, "port", target.Port),
                    Name = JsonString(body, "name"),
                    Dir = JsonString(body, "dir"),
                };
            }
            catch
            {
                return null;
            }
        }

        /// <summary>建目录（相对接收目录的路径，用 / 分段）。</summary>
        public static string MkDir(PhoneTarget target, string relative)
        {
            Dictionary<string, string> headers = new Dictionary<string, string>();
            headers["X-VV-Path"] = EncodePath(relative);
            return Request(target, "PUT", "/dir", headers, new byte[0], 15000);
        }

        /// <summary>上传一个文件；[relative] 是相对接收目录的路径。</summary>
        public static string Upload(PhoneTarget target, string localPath, string relative, Action<long> onBytes)
        {
            FileInfo info = new FileInfo(localPath);
            Dictionary<string, string> headers = new Dictionary<string, string>();
            headers["X-VV-Path"] = EncodePath(relative);
            headers["X-VV-Name"] = Encode(Path.GetFileName(localPath));
            headers["X-VV-Size"] = info.Length.ToString(CultureInfo.InvariantCulture);
            headers["X-VV-Mtime"] = info.LastWriteTimeUtc.ToString("o", CultureInfo.InvariantCulture);
            // 一定要 using：传进去的是 FileStream，忘了释放会让文件在发送完之后仍被占用，
            // 用户在资源管理器里改名/删除就会「文件正在使用」。
            using (FileStream stream = new FileStream(localPath, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                return Request(target, "PUT", "/upload", headers, stream, onBytes, 300000);
            }
        }

        private static string Request(PhoneTarget target, string method, string path,
            Dictionary<string, string> headers, byte[] body, int timeoutMs)
        {
            using (MemoryStream stream = new MemoryStream(body ?? new byte[0]))
                return Request(target, method, path, headers, stream, null, timeoutMs);
        }

        private static string Request(PhoneTarget target, string method, string path,
            Dictionary<string, string> headers, Stream body, Action<long> onBytes, int timeoutMs)
        {
            using (TcpClient client = new TcpClient())
            {
                client.SendTimeout = timeoutMs;
                client.ReceiveTimeout = timeoutMs;
                // 有上限的连接：对方不在线时 Connect 会按系统超时（可能十几秒）干等，
                // 扫描本网段时会把整轮拖到几十秒，这里自己卡时间。
                Connect(client, target.Host, target.Port, timeoutMs);

                long length = 0;
                if (body != null && body.CanSeek) length = body.Length;

                StringBuilder head = new StringBuilder();
                head.Append(method).Append(' ').Append(path).Append(" HTTP/1.1\r\n");
                head.Append("Host: ").Append(target.Authority).Append("\r\n");
                head.Append("Connection: close\r\n");
                if (body != null) head.Append("Content-Length: ").Append(length.ToString(CultureInfo.InvariantCulture)).Append("\r\n");
                if (headers != null)
                {
                    foreach (KeyValuePair<string, string> pair in headers)
                        head.Append(pair.Key).Append(": ").Append(pair.Value).Append("\r\n");
                }
                head.Append("\r\n");

                NetworkStream network = client.GetStream();
                byte[] headBytes = Encoding.UTF8.GetBytes(head.ToString());
                network.Write(headBytes, 0, headBytes.Length);

                if (body != null)
                {
                    byte[] buffer = new byte[64 * 1024];
                    long written = 0;
                    while (true)
                    {
                        int read = body.Read(buffer, 0, buffer.Length);
                        if (read <= 0) break;
                        network.Write(buffer, 0, read);
                        written += read;
                        if (onBytes != null) onBytes(written);
                    }
                }
                network.Flush();

                using (StreamReader reader = new StreamReader(network, Encoding.UTF8))
                    return reader.ReadToEnd();
            }
        }

        /// <summary>带超时的连接；超时抛 TimeoutException，调用方按「连不上」处理。</summary>
        private static void Connect(TcpClient client, string host, int port, int timeoutMs)
        {
            IAsyncResult pending = client.BeginConnect(host, port, null, null);
            if (!pending.AsyncWaitHandle.WaitOne(timeoutMs))
            {
                try { client.Close(); }
                catch { }
                throw new TimeoutException("连接 " + host + ":" + port + " 超时（" + timeoutMs + " ms）");
            }
            client.EndConnect(pending);
        }

        private static List<string> Candidates(string lastHost)
        {
            List<string> hosts = new List<string>();
            if (!string.IsNullOrEmpty(lastHost)) hosts.Add(lastHost);
            hosts.Add("255.255.255.255");
            foreach (string host in LocalBroadcasts())
                if (!hosts.Contains(host)) hosts.Add(host);
            return hosts;
        }

        private static List<string> LocalBroadcasts()
        {
            List<string> result = new List<string>();
            try
            {
                foreach (System.Net.NetworkInformation.NetworkInterface nic in
                    System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (nic.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                    foreach (System.Net.NetworkInformation.UnicastIPAddressInformation address in
                        nic.GetIPProperties().UnicastAddresses)
                    {
                        if (address.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                        byte[] bytes = address.Address.GetAddressBytes();
                        byte[] mask = address.IPv4Mask.GetAddressBytes();
                        if (mask.Length != 4) continue;
                        byte[] broadcast = new byte[4];
                        for (int i = 0; i < 4; i++) broadcast[i] = (byte)(bytes[i] | (mask[i] ^ 255));
                        result.Add(new IPAddress(broadcast).ToString());
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Write("取网卡广播地址失败：" + ex.Message);
            }
            return result;
        }

        /// <summary>并行扫本网段：32 路并发、每台 250 ms，谁先应答就用谁。</summary>
        private static PhoneTarget SweepAndPing(int port)
        {
            List<string> hosts = Sweep();
            PhoneTarget found = null;
            object gate = new object();
            System.Threading.Tasks.ParallelOptions options = new System.Threading.Tasks.ParallelOptions();
            options.MaxDegreeOfParallelism = 32;
            System.Threading.Tasks.Parallel.For(0, hosts.Count, options, delegate(int index, System.Threading.Tasks.ParallelLoopState state)
            {
                if (found != null) { state.Stop(); return; }
                PhoneTarget hit = Ping(new PhoneTarget { Host = hosts[index], Port = port, PingTimeoutMs = 250 });
                if (hit == null) return;
                lock (gate) { if (found == null) found = hit; }
                state.Stop();
            });
            return found;
        }

        /// <summary>本机所在 /24 的 1..254，跳过自己；广播被拦时用它兜底。</summary>
        private static List<string> Sweep()
        {
            List<string> result = new List<string>();
            try
            {
                foreach (System.Net.NetworkInformation.NetworkInterface nic in
                    System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (nic.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                    foreach (System.Net.NetworkInformation.UnicastIPAddressInformation address in
                        nic.GetIPProperties().UnicastAddresses)
                    {
                        if (address.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                        string self = address.Address.ToString();
                        if (self.StartsWith("127.")) continue;
                        string prefix = self.Substring(0, self.LastIndexOf('.') + 1);
                        for (int i = 1; i < 255; i++)
                        {
                            string candidate = prefix + i.ToString(CultureInfo.InvariantCulture);
                            if (candidate != self) result.Add(candidate);
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Write("取本网段失败：" + ex.Message);
            }
            return result;
        }

        private static PhoneTarget Parse(byte[] reply, string fromHost, int fallbackPort)
        {
            string text = Encoding.UTF8.GetString(reply);
            if (text.IndexOf("\"vvfile-phone\"", StringComparison.Ordinal) < 0) return null;
            return new PhoneTarget
            {
                Host = fromHost,
                Port = JsonNumber(text, "port", fallbackPort),
                Name = JsonString(text, "name"),
                Dir = JsonString(text, "dir"),
            };
        }

        private static string JsonString(string json, string key)
        {
            string marker = "\"" + key + "\"";
            int at = json.IndexOf(marker, StringComparison.Ordinal);
            if (at < 0) return null;
            int colon = json.IndexOf(':', at + marker.Length);
            if (colon < 0) return null;
            int start = json.IndexOf('"', colon + 1);
            if (start < 0) return null;
            StringBuilder value = new StringBuilder();
            for (int i = start + 1; i < json.Length; i++)
            {
                char c = json[i];
                if (c == '\\' && i + 1 < json.Length)
                {
                    char next = json[++i];
                    if (next == 'n') value.Append('\n');
                    else if (next == 'r') value.Append('\r');
                    else if (next == 't') value.Append('\t');
                    else if (next == 'u' && i + 4 < json.Length)
                    {
                        int code;
                        if (int.TryParse(json.Substring(i + 1, 4), NumberStyles.HexNumber, CultureInfo.InvariantCulture, out code))
                        {
                            value.Append((char)code);
                            i += 4;
                        }
                    }
                    else value.Append(next);
                    continue;
                }
                if (c == '"') break;
                value.Append(c);
            }
            return value.ToString();
        }

        private static int JsonNumber(string json, string key, int fallback)
        {
            string marker = "\"" + key + "\"";
            int at = json.IndexOf(marker, StringComparison.Ordinal);
            if (at < 0) return fallback;
            int colon = json.IndexOf(':', at + marker.Length);
            if (colon < 0) return fallback;
            int index = colon + 1;
            while (index < json.Length && char.IsWhiteSpace(json[index])) index++;
            int start = index;
            while (index < json.Length && char.IsDigit(json[index])) index++;
            int value;
            if (start == index) return fallback;
            return int.TryParse(json.Substring(start, index - start), out value) ? value : fallback;
        }

        /// <summary>逐段百分号编码（保留 / 作为层级分隔）。</summary>
        public static string EncodePath(string relative)
        {
            string[] parts = relative.Replace('\\', '/').Split('/');
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < parts.Length; i++)
            {
                if (parts[i].Length == 0) continue;
                if (builder.Length > 0) builder.Append('/');
                builder.Append(Encode(parts[i]));
            }
            return builder.ToString();
        }

        private static string Encode(string text)
        {
            return Uri.EscapeDataString(text ?? string.Empty);
        }
    }
}


