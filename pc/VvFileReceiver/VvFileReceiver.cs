// VVFile 接收器 —— 电脑端小服务，接收手机 VVFile 发来的文件。
//
// 形态：无窗口、不在任务栏出现，常驻托盘（右键有菜单），开机自启（HKCU\Run），
// 收到文件弹 Windows 通知。日志写 %LOCALAPPDATA%\VVFileReceiver\receiver.log。
//
// 协议（HTTP/1.1，一条请求一个文件）：
//   GET  /ping                      → {"ok":true,"app":"vvfile-receiver","version":1,"dir":"E:\\VVFile"}
//   PUT  /upload                    → 请求头 X-VV-Name（UTF-8 百分号编码）、X-VV-Size、
//                                     X-VV-Mtime（毫秒，可选）、X-VV-Token（可选）；正文是文件原始字节
//                                     → {"ok":true,"path":"E:\\VVFile\\a.apk","bytes":123,"renamed":false}
//   失败统一 {"ok":false,"error":"..."}，HTTP 状态码同时给出。
//
// 编译：pc/VvFileReceiver/build.ps1（用系统自带 csc.exe，.NET Framework 4.x，无需装 SDK）

using System;
using System.Collections.Generic;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

namespace VvFileReceiver
{
    internal sealed class Options
    {
        public string Dir = @"E:\VVFile";
        public int Port = 7717;
        public bool Uninstall;
        public bool ShowHelp;

        public static Options Parse(string[] args)
        {
            Options o = new Options();
            for (int i = 0; i < args.Length; i++)
            {
                string a = args[i].ToLowerInvariant();
                switch (a)
                {
                    case "--dir":
                        if (i + 1 < args.Length) o.Dir = args[++i];
                        break;
                    case "--port":
                        if (i + 1 < args.Length)
                        {
                            int p;
                            if (int.TryParse(args[++i], out p) && p > 0 && p < 65536) o.Port = p;
                        }
                        break;

                    case "--uninstall":
                        o.Uninstall = true;
                        break;
                    case "-h":
                    case "--help":
                    case "/?":
                        o.ShowHelp = true;
                        break;
                }
            }
            return o;
        }

        public const string HelpText =
            "VVFile 接收器\r\n\r\n" +
            "  --dir <目录>     接收目录，默认 E:\\VVFile\r\n" +
            "  --port <端口>    监听端口，默认 7717\r\n" +
            "  --uninstall      取消开机启动并退出\r\n\r\n" +
            "启动后常驻托盘，收到文件会弹 Windows 通知。";
    }

    internal static class Log
    {
        private static readonly object Gate = new object();
        private static string _path;

        public static string Path
        {
            get
            {
                if (_path == null)
                {
                    string dir = System.IO.Path.Combine(
                        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                        "VVFileReceiver");
                    Directory.CreateDirectory(dir);
                    _path = System.IO.Path.Combine(dir, "receiver.log");
                }
                return _path;
            }
        }

        public static void Write(string message)
        {
            try
            {
                lock (Gate)
                {
                    // 超过 2 MB 就轮转一次，长期挂机不至于把盘写满
                    FileInfo info = new FileInfo(Path);
                    if (info.Exists && info.Length > 2 * 1024 * 1024)
                    {
                        string old = Path + ".1";
                        if (File.Exists(old)) File.Delete(old);
                        File.Move(Path, old);
                    }
                    File.AppendAllText(
                        Path,
                        string.Format("[{0}] {1}\r\n", DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss"), message),
                        Encoding.UTF8);
                }
            }
            catch
            {
                // 日志写不进去也不该影响收文件
            }
        }
    }

    /** HKCU\Software\Microsoft\Windows\CurrentVersion\Run —— 不需要管理员权限的开机自启。 */
    internal static class Autostart
    {
        private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        private const string ValueName = "VVFileReceiver";

        public static void Ensure(Options opts)
        {
            try
            {
                string exe = Application.ExecutablePath;
                string cmd = string.Format("\"{0}\" --dir \"{1}\" --port {2}", exe, opts.Dir, opts.Port);
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(RunKey, true))
                {
                    if (key == null) return;
                    object current = key.GetValue(ValueName);
                    if (current != null && string.Equals(current.ToString(), cmd, StringComparison.OrdinalIgnoreCase)) return;
                    key.SetValue(ValueName, cmd);
                    Log.Write("已注册开机启动：" + cmd);
                }
            }
            catch (Exception ex)
            {
                Log.Write("注册开机启动失败：" + ex.Message);
            }
        }

        public static void Remove()
        {
            try
            {
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(RunKey, true))
                {
                    if (key != null) key.DeleteValue(ValueName, false);
                }
                Log.Write("已取消开机启动");
            }
            catch (Exception ex)
            {
                Log.Write("取消开机启动失败：" + ex.Message);
            }
        }
    }

    internal static class Program
    {
        [STAThread]
        private static void Main(string[] args)
        {
            Options opts = Options.Parse(args);

            if (opts.ShowHelp)
            {
                MessageBox.Show(Options.HelpText, "VVFile 接收器", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            if (opts.Uninstall)
            {
                Autostart.Remove();
                MessageBox.Show("已取消开机启动。以后可以手动删掉这个 exe。", "VVFile 接收器",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }

            bool createdNew;
            using (Mutex mutex = new Mutex(true, @"Local\VvFileReceiver-" + opts.Port, out createdNew))
            {
                if (!createdNew) return; // 已经在跑了，静默退出

                Autostart.Ensure(opts);
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);

                try
                {
                    using (TrayContext context = new TrayContext(opts))
                    {
                        Application.Run(context);
                    }
                }
                catch (SocketException ex)
                {
                    Log.Write("端口监听失败：" + ex.Message);
                    MessageBox.Show(
                        string.Format("端口 {0} 起不来：{1}\r\n换一个端口再试（--port 7718）。", opts.Port, ex.Message),
                        "VVFile 接收器", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
                GC.KeepAlive(mutex);
            }
        }
    }

    /**
     * 局域网自动发现：手机往 UDP 7717 广播 VVFILE?DISCOVER=1，这里回一串 JSON。
     *
     * 手机只认「包从哪来」，地址取源 IP，所以电脑多网卡也不会指错路；
     * 手机端不用填 IP，也没有口令。
     */
    internal sealed class Discovery : IDisposable
    {
        private readonly Options _opts;
        private readonly UdpClient _udp;
        private volatile bool _running = true;

        public Discovery(Options opts)
        {
            _opts = opts;
            _udp = new UdpClient(new IPEndPoint(IPAddress.Any, opts.Port));
            _udp.EnableBroadcast = true;
        }

        public void Start()
        {
            Thread worker = new Thread(Loop);
            worker.IsBackground = true;
            worker.Name = "vvfile-discovery";
            worker.Start();
        }

        private void Loop()
        {
            IPEndPoint remote = new IPEndPoint(IPAddress.Any, 0);
            while (_running)
            {
                try
                {
                    byte[] data = _udp.Receive(ref remote);
                    string text = Encoding.UTF8.GetString(data);
                    if (text.IndexOf("VVFILE", StringComparison.OrdinalIgnoreCase) < 0) continue;

                    string reply = string.Format(
                        "{{\"ok\":true,\"app\":\"vvfile-receiver\",\"version\":1,\"port\":{0},\"dir\":{1},\"host\":{2}}}",
                        _opts.Port, TrayContext.JsonString(_opts.Dir),
                        TrayContext.JsonString(Environment.MachineName));
                    byte[] bytes = Encoding.UTF8.GetBytes(reply);
                    _udp.Send(bytes, bytes.Length, remote);
                    Log.Write(string.Format("{0} 广播发现，已回应", remote.Address));
                }
                catch (SocketException)
                {
                    if (!_running) return;
                }
                catch (ObjectDisposedException)
                {
                    return;
                }
                catch (Exception ex)
                {
                    if (!_running) return;
                    Log.Write("广播发现出错：" + ex.Message);
                }
            }
        }

        public void Dispose()
        {
            _running = false;
            try { _udp.Close(); }
            catch { }
        }
    }

    /** 托盘 + 后台监听；没有 Form，所以不会出现在任务栏。 */
    internal sealed class TrayContext : ApplicationContext
    {
        private readonly Options _opts;
        private readonly NotifyIcon _tray;
        private readonly SynchronizationContext _ui;
        private readonly TcpListener _listener;
        private Discovery _discovery;

        public TrayContext(Options opts)
        {
            _opts = opts;
            _ui = SynchronizationContext.Current ?? new WindowsFormsSynchronizationContext();

            try
            {
                Directory.CreateDirectory(opts.Dir);
            }
            catch (Exception ex)
            {
                Log.Write("接收目录创建失败：" + ex.Message);
            }

            _listener = new TcpListener(IPAddress.Any, opts.Port);
            _listener.Start();
            Log.Write(string.Format("开始监听 0.0.0.0:{0}，接收目录 {1}", opts.Port, opts.Dir));

            _tray = new NotifyIcon();
            _tray.Icon = SystemIcons.Application;
            _tray.Text = "VVFile 接收器 · 端口 " + opts.Port;
            _tray.ContextMenuStrip = BuildMenu();
            _tray.DoubleClick += delegate { OpenFolder(); };
            _tray.Visible = true;

            Thread worker = new Thread(ServeLoop);
            worker.IsBackground = true;
            worker.Name = "vvfile-receiver";
            worker.Start();

            try
            {
                _discovery = new Discovery(opts);
                _discovery.Start();
                Log.Write(string.Format("局域网自动发现已开启（UDP {0} 广播）", opts.Port));
            }
            catch (Exception ex)
            {
                Log.Write("自动发现开启失败：" + ex.Message);
            }

            Notify(
                "VVFile 接收器已启动",
                string.Format("保存到 {0}\r\n手机端会自动发现这台电脑", opts.Dir));
        }

        private ContextMenuStrip BuildMenu()
        {
            ContextMenuStrip menu = new ContextMenuStrip();

            ToolStripMenuItem open = new ToolStripMenuItem("打开接收文件夹");
            open.Click += delegate { OpenFolder(); };
            menu.Items.Add(open);

            ToolStripMenuItem copy = new ToolStripMenuItem("复制本机地址（备用）");
            copy.Click += delegate
            {
                try
                {
                    Clipboard.SetText(string.Format("{0}:{1}", BestAddress(), _opts.Port));
                    Notify("地址已复制", string.Format("{0}:{1}", BestAddress(), _opts.Port));
                }
                catch (Exception ex)
                {
                    Log.Write("复制地址失败：" + ex.Message);
                }
            };
            menu.Items.Add(copy);

            ToolStripMenuItem help = new ToolStripMenuItem("手机端怎么用");
            help.Click += delegate
            {
                MessageBox.Show(
                    "1. 手机长按文件 → 发送到电脑；或别的应用里「打开方式」选「发送到电脑」。\r\n" +
                    "2. 手机和电脑连同一个 WiFi，手机会自动发现这台电脑。\r\n" +
                    "3. 不用填 IP，也没有口令；地址一般不用管（备用：" + BestAddress() + ":" + _opts.Port + "）。\r\n\r\n" +
                    "接收目录：" + _opts.Dir + "\r\n" +
                    "日志：" + Log.Path,
                    "VVFile 接收器", MessageBoxButtons.OK, MessageBoxIcon.Information);
            };
            menu.Items.Add(help);

            ToolStripMenuItem logItem = new ToolStripMenuItem("打开日志");
            logItem.Click += delegate
            {
                try
                {
                    System.Diagnostics.Process.Start("notepad.exe", "\"" + Log.Path + "\"");
                }
                catch (Exception ex)
                {
                    Log.Write("打开日志失败：" + ex.Message);
                }
            };
            menu.Items.Add(logItem);

            menu.Items.Add(new ToolStripSeparator());

            ToolStripMenuItem exit = new ToolStripMenuItem("退出");
            exit.Click += delegate
            {
                try { if (_discovery != null) _discovery.Dispose(); }
                catch { }
                try { _listener.Stop(); }
                catch { }
                _tray.Visible = false;
                ExitThread();
            };
            menu.Items.Add(exit);

            return menu;
        }

        private void OpenFolder()
        {
            try
            {
                Directory.CreateDirectory(_opts.Dir);
                System.Diagnostics.Process.Start("explorer.exe", "\"" + _opts.Dir + "\"");
            }
            catch (Exception ex)
            {
                Log.Write("打开目录失败：" + ex.Message);
            }
        }

        private void Notify(string title, string text)
        {
            // 托盘气泡在 Win10/11 上就是系统的通知横幅
            _ui.Post(delegate
            {
                try
                {
                    _tray.ShowBalloonTip(4000, title, text, ToolTipIcon.Info);
                }
                catch
                {
                }
            }, null);
        }

        /** 找一个像样的局域网地址给用户照着填；找不到就用主机名。 */
        private static string BestAddress()
        {
            try
            {
                string fallback = null;
                foreach (IPAddress ip in Dns.GetHostAddresses(Dns.GetHostName()))
                {
                    if (ip.AddressFamily != AddressFamily.InterNetwork) continue;
                    byte[] b = ip.GetAddressBytes();
                    if (b[0] == 127) continue;
                    // 常见私网段优先，虚拟网卡（169.254）最后
                    if (b[0] == 192 && b[1] == 168) return ip.ToString();
                    if (b[0] == 10) { if (fallback == null) fallback = ip.ToString(); }
                    if (b[0] == 172 && b[1] >= 16 && b[1] <= 31) { if (fallback == null) fallback = ip.ToString(); }
                    if (fallback == null && !(b[0] == 169 && b[1] == 254)) fallback = ip.ToString();
                }
                if (fallback != null) return fallback;
                return Dns.GetHostName();
            }
            catch
            {
                return "127.0.0.1";
            }
        }

        private void ServeLoop()
        {
            while (true)
            {
                TcpClient client;
                try
                {
                    client = _listener.AcceptTcpClient();
                }
                catch (Exception ex)
                {
                    Log.Write("监听结束：" + ex.Message);
                    return;
                }
                ThreadPool.QueueUserWorkItem(delegate(object state) { Handle((TcpClient)state); }, client);
            }
        }

        private void Handle(TcpClient client)
        {
            string remote = "?";
            try
            {
                remote = ((IPEndPoint)client.Client.RemoteEndPoint).Address.ToString();
                client.ReceiveTimeout = 30000;
                client.SendTimeout = 30000;
                using (client)
                using (NetworkStream ns = client.GetStream())
                {
                    string method;
                    string path;
                    Dictionary<string, string> headers;
                    if (!ReadHead(ns, out method, out path, out headers))
                    {
                        WriteJson(ns, 400, "{\"ok\":false,\"error\":\"请求格式不对\"}");
                        return;
                    }

                    if (method == "GET")
                    {
                        if (path.StartsWith("/ping"))
                        {
                            WriteJson(ns, 200, string.Format(
                                "{{\"ok\":true,\"app\":\"vvfile-receiver\",\"version\":1,\"dir\":{0}}}",
                                JsonString(_opts.Dir)));
                        }
                        else
                        {
                            string html = "<html><head><meta charset=\"utf-8\"><title>VVFile 接收器</title></head>" +
                                "<body style=\"font-family:Segoe UI,sans-serif;padding:24px\">" +
                                "<h2>VVFile 接收器在跑</h2><p>接收目录：<code>" + Escape(_opts.Dir) + "</code></p>" +
                                "<p>手机端在 VVFile 里长按文件 → 发送到电脑，同一 WiFi 下会自动找到这台电脑。</p>" +
                                "</body></html>";
                            WriteText(ns, 200, "text/html; charset=utf-8", html);
                        }
                        return;
                    }

                    if (method != "PUT" && method != "POST")
                    {
                        WriteJson(ns, 405, "{\"ok\":false,\"error\":\"只支持 PUT /upload\"}");
                        return;
                    }



                    if (!headers.ContainsKey("Content-Length"))
                    {
                        WriteJson(ns, 411, "{\"ok\":false,\"error\":\"缺少 Content-Length\"}");
                        return;
                    }

                    long length;
                    if (!long.TryParse(headers["Content-Length"], out length) || length < 0)
                    {
                        WriteJson(ns, 400, "{\"ok\":false,\"error\":\"Content-Length 不认识\"}");
                        return;
                    }

                    string rawName;
                    headers.TryGetValue("X-VV-Name", out rawName);
                    string name = CleanName(rawName);

                    // 相对路径（文件夹里的文件带层级）；没给就按文件名落
                    string rawPath;
                    headers.TryGetValue("X-VV-Path", out rawPath);
                    string relative = SafeRelative(rawPath, name);

                    if (path.StartsWith("/dir"))
                    {
                        CreateDir(ns, remote, relative);
                        return;
                    }

                    ReceiveFile(ns, remote, relative, name, length);
                }
            }
            catch (Exception ex)
            {
                Log.Write(string.Format("{0} 处理失败：{1}", remote, ex.Message));
            }
        }

        private void ReceiveFile(NetworkStream ns, string remote, string relative, string name, long length)
        {
            Directory.CreateDirectory(_opts.Dir);

            // 相对路径逐段拼到接收目录下：照片/2024/a.jpg 会在 E:\VVFile\照片\2024 里落盘
            string target = System.IO.Path.Combine(_opts.Dir, relative.Replace('/', '\\'));
            string targetDir = System.IO.Path.GetDirectoryName(target);
            Directory.CreateDirectory(targetDir);

            string temp = System.IO.Path.Combine(
                targetDir,
                string.Format(".incoming-{0}.part", Guid.NewGuid().ToString("N")));

            long received = 0;
            DateTime started = DateTime.Now;
            long nextReport = 4L * 1024 * 1024;
            try
            {
                using (FileStream fs = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                          64 * 1024, FileOptions.SequentialScan))
                {
                    byte[] buffer = new byte[64 * 1024];
                    while (received < length)
                    {
                        int want = (int)Math.Min(buffer.Length, length - received);
                        int read = ns.Read(buffer, 0, want);
                        if (read <= 0) throw new IOException("连接中断，只收到 " + received + " 字节");
                        fs.Write(buffer, 0, read);
                        received += read;
                        if (received >= nextReport && length > 0)
                        {
                            nextReport += 4L * 1024 * 1024;
                            Log.Write(string.Format("{0} 接收 {1}：{2}/{3}（{4}）", remote, relative,
                                SizeText(received), SizeText(length), SpeedText(received, started)));
                        }
                    }
                }

                string final = UniqueTarget(targetDir, System.IO.Path.GetFileName(target));
                File.Move(temp, final);
                bool renamed = !string.Equals(System.IO.Path.GetFileName(final),
                    System.IO.Path.GetFileName(target), StringComparison.Ordinal);

                Log.Write(string.Format("{0} 已保存 {1}（{2}，{3}，用时 {4:0.0}s）", remote,
                    final, SizeText(received), SpeedText(received, started),
                    (DateTime.Now - started).TotalSeconds));

                Notify("收到文件", RelativeShown(final) + "\r\n" + SizeText(received));

                WriteJson(ns, 200, string.Format(
                    "{{\"ok\":true,\"path\":{0},\"bytes\":{1},\"renamed\":{2}}}",
                    JsonString(final), received, renamed ? "true" : "false"));
            }
            catch (Exception ex)
            {
                try
                {
                    if (File.Exists(temp)) File.Delete(temp);
                }
                catch
                {
                }
                Log.Write(string.Format("{0} 保存 {1} 失败：{2}", remote, relative, ex.Message));
                WriteJson(ns, 500, "{\"ok\":false,\"error\":" + JsonString(ex.Message) + "}");
            }
        }

        /** 手机端发来的「建目录」请求：只建目录，没有正文。 */
        private void CreateDir(NetworkStream ns, string remote, string relative)
        {
            try
            {
                string target = System.IO.Path.Combine(_opts.Dir, relative.Replace('/', '\\'));
                Directory.CreateDirectory(target);
                Log.Write(string.Format("{0} 已建目录 {1}", remote, relative + "/"));
                WriteJson(ns, 200, string.Format("{{\"ok\":true,\"path\":{0}}}", JsonString(target)));
            }
            catch (Exception ex)
            {
                Log.Write(string.Format("{0} 建目录 {1} 失败：{2}", remote, relative, ex.Message));
                WriteJson(ns, 500, "{\"ok\":false,\"error\":" + JsonString(ex.Message) + "}");
            }
        }

        /** 日志/提示里显示相对接收目录的短路径。 */
        private string RelativeShown(string absolute)
        {
            try
            {
                string root = _opts.Dir.TrimEnd('\\') + "\\";
                return absolute.StartsWith(root, StringComparison.OrdinalIgnoreCase)
                    ? absolute.Substring(root.Length)
                    : System.IO.Path.GetFileName(absolute);
            }
            catch
            {
                return System.IO.Path.GetFileName(absolute);
            }
        }

        private static string SpeedText(long bytes, DateTime started)
        {
            double seconds = (DateTime.Now - started).TotalSeconds;
            if (seconds < 0.2) return "-";
            return SizeText((long)(bytes / seconds)) + "/s";
        }

        internal static string SizeText(long bytes)
        {
            string[] units = new string[] { "B", "KB", "MB", "GB", "TB" };
            double value = bytes;
            int unit = 0;
            while (value >= 1024 && unit < units.Length - 1)
            {
                value /= 1024;
                unit++;
            }
            return unit == 0
                ? string.Format("{0} {1}", bytes, units[0])
                : string.Format(CultureInfo.InvariantCulture, "{0:0.0} {1}", value, units[unit]);
        }

        /** 百分号解码 → 只留文件名 → 去掉非法字符。 */
        internal static string CleanName(string raw)
        {
            string name = null;
            if (!string.IsNullOrEmpty(raw))
            {
                try
                {
                    name = Uri.UnescapeDataString(raw);
                }
                catch
                {
                    name = raw;
                }
            }
            if (!string.IsNullOrEmpty(name))
            {
                name = name.Replace('\\', '/');
                int cut = name.LastIndexOf('/');
                if (cut >= 0) name = name.Substring(cut + 1);
                char[] invalid = System.IO.Path.GetInvalidFileNameChars();
                StringBuilder builder = new StringBuilder(name.Length);
                foreach (char c in name)
                {
                    builder.Append(Array.IndexOf(invalid, c) >= 0 ? '_' : c);
                }
                name = builder.ToString().Trim().TrimEnd('.');
                if (name.Length > 180) name = name.Substring(0, 180);
                if (name == "." || name == "..") name = null;
            }
            if (string.IsNullOrEmpty(name))
            {
                name = "vvfile-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".bin";
            }
            return name;
        }

        /**
         * 相对路径净化：按 `/` 分段，丢掉空段与 `.`/`..`（防目录穿越），
         * 每段过滤非法字符、去掉尾部点、限长，最后用 `/` 拼回来。
         * 全被过滤掉时退回 [fallback]（文件名）。
         */
        internal static string SafeRelative(string rawPath, string fallback)
        {
            string decoded = rawPath;
            if (!string.IsNullOrEmpty(rawPath))
            {
                try
                {
                    decoded = Uri.UnescapeDataString(rawPath);
                }
                catch
                {
                    decoded = rawPath;
                }
            }
            if (string.IsNullOrEmpty(decoded)) return fallback;

            char[] invalid = System.IO.Path.GetInvalidFileNameChars();
            string[] parts = decoded.Replace('\\', '/').Split('/');
            StringBuilder builder = new StringBuilder();
            int depth = 0;
            foreach (string part in parts)
            {
                string segment = part.Trim();
                if (segment.Length == 0 || segment == "." || segment == "..") continue;
                StringBuilder cleaned = new StringBuilder(segment.Length);
                foreach (char c in segment)
                {
                    cleaned.Append(Array.IndexOf(invalid, c) >= 0 ? '_' : c);
                }
                string safe = cleaned.ToString().Trim().TrimEnd('.');
                if (safe.Length == 0) continue;
                if (safe.Length > 120) safe = safe.Substring(0, 120);
                if (depth >= 24) break;
                if (builder.Length > 0) builder.Append('/');
                builder.Append(safe);
                depth++;
            }
            return builder.Length == 0 ? fallback : builder.ToString();
        }

        /** 重名不覆盖：a.apk → a (1).apk → a (2).apk。 */
        internal static string UniqueTarget(string dir, string name)
        {
            string candidate = System.IO.Path.Combine(dir, name);
            if (!File.Exists(candidate)) return candidate;
            string baseName = System.IO.Path.GetFileNameWithoutExtension(name);
            string ext = System.IO.Path.GetExtension(name);
            for (int i = 1; i < 10000; i++)
            {
                candidate = System.IO.Path.Combine(dir, string.Format("{0} ({1}){2}", baseName, i, ext));
                if (!File.Exists(candidate)) return candidate;
            }
            return System.IO.Path.Combine(dir, Guid.NewGuid().ToString("N") + ext);
        }

        private static bool ReadHead(Stream stream, out string method, out string path,
            out Dictionary<string, string> headers)
        {
            method = null;
            path = null;
            headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

            MemoryStream buffer = new MemoryStream();
            byte[] tail = new byte[4];
            while (buffer.Length < 32 * 1024)
            {
                int b = stream.ReadByte();
                if (b < 0) return false;
                buffer.WriteByte((byte)b);
                tail[0] = tail[1];
                tail[1] = tail[2];
                tail[2] = tail[3];
                tail[3] = (byte)b;
                if (tail[0] == 13 && tail[1] == 10 && tail[2] == 13 && tail[3] == 10) break;
            }
            byte[] all = buffer.ToArray();
            if (all.Length < 4) return false;
            string text = Encoding.UTF8.GetString(all, 0, all.Length - 4);
            string[] lines = text.Split(new string[] { "\r\n" }, StringSplitOptions.None);
            if (lines.Length == 0) return false;

            string[] request = lines[0].Split(' ');
            if (request.Length < 2) return false;
            method = request[0].ToUpperInvariant();
            path = request[1];

            for (int i = 1; i < lines.Length; i++)
            {
                int colon = lines[i].IndexOf(':');
                if (colon <= 0) continue;
                headers[lines[i].Substring(0, colon).Trim()] = lines[i].Substring(colon + 1).Trim();
            }
            return true;
        }

        private static void WriteJson(Stream stream, int status, string json)
        {
            WriteText(stream, status, "application/json; charset=utf-8", json);
        }

        private static void WriteText(Stream stream, int status, string contentType, string body)
        {
            byte[] payload = Encoding.UTF8.GetBytes(body);
            string head = string.Format(
                "HTTP/1.1 {0} {1}\r\nContent-Type: {2}\r\nContent-Length: {3}\r\nConnection: close\r\n\r\n",
                status, Reason(status), contentType, payload.Length);
            byte[] headBytes = Encoding.ASCII.GetBytes(head);
            stream.Write(headBytes, 0, headBytes.Length);
            stream.Write(payload, 0, payload.Length);
            stream.Flush();
        }

        private static string Reason(int status)
        {
            switch (status)
            {
                case 200: return "OK";
                case 400: return "Bad Request";
                case 403: return "Forbidden";
                case 405: return "Method Not Allowed";
                case 411: return "Length Required";
                default: return "Error";
            }
        }

        internal static string JsonString(string value)
        {
            if (value == null) return "null";
            StringBuilder builder = new StringBuilder("\"");
            foreach (char c in value)
            {
                switch (c)
                {
                    case '"': builder.Append("\\\""); break;
                    case '\\': builder.Append("\\\\"); break;
                    case '\n': builder.Append("\\n"); break;
                    case '\r': builder.Append("\\r"); break;
                    case '\t': builder.Append("\\t"); break;
                    default:
                        if (c < 0x20) builder.AppendFormat("\\u{0:x4}", (int)c);
                        else builder.Append(c);
                        break;
                }
            }
            return builder.Append('"').ToString();
        }

        internal static string Escape(string value)
        {
            return value == null
                ? string.Empty
                : value.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;");
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                try { _listener.Stop(); }
                catch { }
                if (_tray != null)
                {
                    _tray.Visible = false;
                    _tray.Dispose();
                }
            }
            base.Dispose(disposing);
        }
    }
}




