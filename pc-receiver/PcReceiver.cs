using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;

namespace PcReceiver
{
    internal static class Program
    {
        public const string Version = "1.1";
        public const int DiscoveryPort = 8765;

        [STAThread]
        private static void Main()
        {
            bool createdNew;
            using (Mutex mutex = new Mutex(true, "PcReceiver.SingleInstance", out createdNew))
            {
                if (!createdNew)
                {
                    MessageBox.Show(
                        "扫码枪接收器已在运行，请查看系统托盘。",
                        "扫码枪接收器",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Information);
                    return;
                }

                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.ThreadException += delegate(object sender, ThreadExceptionEventArgs e)
                {
                    Log.Write("ui exception: " + e.Exception);
                };
                AppDomain.CurrentDomain.UnhandledException += delegate(object sender, UnhandledExceptionEventArgs e)
                {
                    Log.Write("unhandled exception: " + e.ExceptionObject);
                };

                try
                {
                    Application.Run(new ReceiverContext());
                }
                catch (Exception ex)
                {
                    Log.Write("fatal: " + ex);
                    MessageBox.Show(
                        "接收器异常退出：\r\n" + ex.Message,
                        "扫码枪接收器",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Error);
                }
            }
        }
    }

    internal static class Log
    {
        private static readonly object Sync = new object();
        private static string path;

        public static void Init(string filePath)
        {
            path = filePath;
            try
            {
                if (File.Exists(path) && new FileInfo(path).Length > 1024 * 1024)
                {
                    string backup = path + ".old";
                    if (File.Exists(backup)) File.Delete(backup);
                    File.Move(path, backup);
                }
            }
            catch
            {
                // 日志不是关键路径，失败时继续运行。
            }
        }

        public static void Write(string message)
        {
            if (string.IsNullOrEmpty(path)) return;
            lock (Sync)
            {
                try
                {
                    File.AppendAllText(
                        path,
                        DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "  " + message + Environment.NewLine,
                        new UTF8Encoding(false));
                }
                catch
                {
                }
            }
        }
    }

    internal sealed class ReceiverConfig
    {
        public int Port;
        public int CharDelayMs;
        public int EnterDelayMs;
        public int StartDelayMs;
        public int MaxItems;

        public static ReceiverConfig LoadOrCreate(string path)
        {
            Dictionary<string, string> values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            bool needWrite = !File.Exists(path);
            if (!needWrite)
            {
                try
                {
                    foreach (string raw in File.ReadAllLines(path))
                    {
                        string line = raw.Trim();
                        if (line.Length == 0 || line.StartsWith("#") || line.StartsWith(";")) continue;
                        int equals = line.IndexOf('=');
                        if (equals <= 0) continue;
                        string key = line.Substring(0, equals).Trim();
                        string value = line.Substring(equals + 1).Trim();
                        if (key.Length > 0) values[key] = value;
                    }
                }
                catch (Exception ex)
                {
                    Log.Write("config read failed: " + ex.GetType().Name);
                    needWrite = true;
                }
            }

            ReceiverConfig config = new ReceiverConfig();
            config.Port = ReadInt(values, "port", 8765, 1, 65535);
            config.CharDelayMs = ReadInt(values, "char_delay_ms", 6, 0, 200);
            config.EnterDelayMs = ReadInt(values, "enter_delay_ms", 25, 0, 500);
            config.StartDelayMs = ReadInt(values, "start_delay_ms", 600, 0, 10000);
            config.MaxItems = ReadInt(values, "max_items", 100, 1, 500);

            if (needWrite)
            {
                try
                {
                    Save(path, config);
                }
                catch (Exception ex)
                {
                    Log.Write("config write failed: " + ex.GetType().Name);
                }
            }
            return config;
        }

        private static void Save(string path, ReceiverConfig config)
        {
            StringBuilder text = new StringBuilder();
            text.AppendLine("# 扫码枪接收器配置（修改后请在托盘菜单选择「重新加载配置」）");
            text.AppendLine("# 手机 App 会自动搜索本机；搜索不到时在手机设置页填写 http://本机IP:端口");
            text.AppendLine();
            text.AppendLine("port=" + config.Port);
            text.AppendLine();
            text.AppendLine("# 每个字符之间的输入间隔（毫秒），0 表示不等待");
            text.AppendLine("char_delay_ms=" + config.CharDelayMs);
            text.AppendLine("# 每条条码输入完回车后的等待（毫秒）");
            text.AppendLine("enter_delay_ms=" + config.EnterDelayMs);
            text.AppendLine("# 收到请求后到开始输入前的等待（毫秒），留时间给目标窗口获得焦点");
            text.AppendLine("start_delay_ms=" + config.StartDelayMs);
            text.AppendLine("# 单次最多接受的条码数");
            text.AppendLine("max_items=" + config.MaxItems);
            // 带 BOM：确保记事本正确识别中文注释。
            File.WriteAllText(path, text.ToString(), new UTF8Encoding(true));
        }

        private static int ReadInt(Dictionary<string, string> values, string key, int fallback, int min, int max)
        {
            string text;
            if (!values.TryGetValue(key, out text)) return fallback;
            int value;
            if (!int.TryParse(text, out value)) return fallback;
            if (value < min) return min;
            if (value > max) return max;
            return value;
        }

    }

    /// <summary>
    /// UDP 自动发现：收到 MULTISCAN_DISCOVER_V1 广播后回复本机名称、版本与 HTTP 端口。
    /// 与 HTTP 使用同一端口号（UDP/TCP 端口空间独立）。
    /// </summary>
    internal sealed class DiscoveryResponder
    {
        private const string RequestMagic = "MULTISCAN_DISCOVER_V1";

        private readonly int listenPort;
        private readonly int httpPort;
        private readonly string hostName;
        private UdpClient client;
        private Thread thread;
        private volatile bool running;

        public DiscoveryResponder(int listenPort, int httpPort)
        {
            this.listenPort = listenPort;
            this.httpPort = httpPort;
            this.hostName = SafeHostName();
        }

        public void Start()
        {
            this.Stop();

            UdpClient created = new UdpClient(this.listenPort);
            this.client = created;
            this.running = true;
            this.thread = new Thread(this.Loop);
            this.thread.IsBackground = true;
            this.thread.Name = "udp-discovery";
            this.thread.Start();
            Log.Write("discovery listening on udp " + this.listenPort + " -> http " + this.httpPort +
                      ", name=" + this.hostName);
        }

        public void Stop()
        {
            this.running = false;

            UdpClient current = this.client;
            this.client = null;
            if (current != null)
            {
                try
                {
                    current.Close();
                }
                catch
                {
                }
            }

            Thread currentThread = this.thread;
            this.thread = null;
            if (currentThread != null && currentThread != Thread.CurrentThread)
            {
                try
                {
                    currentThread.Join(300);
                }
                catch
                {
                }
            }
        }

        private void Loop()
        {
            UdpClient active = this.client;
            if (active == null) return;

            IPEndPoint remote = new IPEndPoint(IPAddress.Any, 0);
            while (this.running)
            {
                try
                {
                    byte[] data = active.Receive(ref remote);
                    if (data == null) continue;
                    string request = Encoding.UTF8.GetString(data).Trim();
                    if (!string.Equals(request, RequestMagic, StringComparison.Ordinal)) continue;

                    string json = "{\"ok\":true,\"service\":\"multiscan-pc\",\"version\":\"" +
                                  Program.Version + "\",\"port\":" + this.httpPort +
                                  ",\"name\":\"" + JsonEscape(this.hostName) + "\"}";
                    byte[] response = Encoding.UTF8.GetBytes(json);
                    active.Send(response, response.Length, remote);
                }
                catch (SocketException)
                {
                    if (this.running) Log.Write("discovery socket error");
                    else break;
                }
                catch (ObjectDisposedException)
                {
                    break;
                }
                catch (Exception ex)
                {
                    if (!this.running) break;
                    Log.Write("discovery error: " + ex.GetType().Name);
                }
            }
        }

        private static string SafeHostName()
        {
            try
            {
                return Dns.GetHostName();
            }
            catch
            {
                return "PC";
            }
        }

        private static string JsonEscape(string text)
        {
            if (string.IsNullOrEmpty(text)) return string.Empty;
            StringBuilder escaped = new StringBuilder(text.Length + 8);
            for (int i = 0; i < text.Length; i++)
            {
                char c = text[i];
                if (c == '"' || c == '\\') escaped.Append('\\').Append(c);
                else if (c < 0x20) escaped.Append(' ');
                else escaped.Append(c);
            }
            return escaped.ToString();
        }
    }

    internal sealed class ReceiverContext : ApplicationContext
    {
        private readonly string configPath;
        private readonly Form hiddenForm;
        private ReceiverConfig config;
        private HttpServer server;
        private DiscoveryResponder discovery;
        private NotifyIcon notifyIcon;
        private ContextMenuStrip menu;
        private ToolStripMenuItem addressItem;
        private ToolStripMenuItem statusItem;
        private string lastError;

        public ReceiverContext()
        {
            string baseDirectory = Application.StartupPath;
            this.configPath = Path.Combine(baseDirectory, "PcReceiver.ini");
            Log.Init(Path.Combine(baseDirectory, "PcReceiver.log"));
            Log.Write("receiver starting, version " + Program.Version + ", directory " + baseDirectory);

            this.hiddenForm = new Form();
            this.hiddenForm.Text = "PcReceiver";
            this.hiddenForm.ShowInTaskbar = false;
            this.hiddenForm.FormBorderStyle = FormBorderStyle.None;
            this.hiddenForm.WindowState = FormWindowState.Minimized;
            IntPtr unused = this.hiddenForm.Handle;

            this.config = ReceiverConfig.LoadOrCreate(this.configPath);
            this.BuildTray();
            this.StartServer();
            this.ShowStartupBalloon();
        }

        private void BuildTray()
        {
            this.addressItem = new ToolStripMenuItem("地址：…");
            this.addressItem.ToolTipText = "点击复制";
            this.addressItem.Click += this.OnCopyAddress;

            this.statusItem = new ToolStripMenuItem("状态：…");
            this.statusItem.Enabled = false;

            ToolStripMenuItem reloadItem = new ToolStripMenuItem("重新加载配置");
            reloadItem.Click += this.OnReload;

            ToolStripMenuItem openConfigItem = new ToolStripMenuItem("打开配置文件");
            openConfigItem.Click += this.OnOpenConfig;

            ToolStripMenuItem openLogItem = new ToolStripMenuItem("查看日志");
            openLogItem.Click += this.OnOpenLog;

            ToolStripMenuItem exitItem = new ToolStripMenuItem("退出");
            exitItem.Click += this.OnExit;

            this.menu = new ContextMenuStrip();
            this.menu.Opening += this.OnMenuOpening;
            this.menu.Items.Add(this.addressItem);
            this.menu.Items.Add(new ToolStripSeparator());
            this.menu.Items.Add(reloadItem);
            this.menu.Items.Add(openConfigItem);
            this.menu.Items.Add(openLogItem);
            this.menu.Items.Add(new ToolStripSeparator());
            this.menu.Items.Add(this.statusItem);
            this.menu.Items.Add(new ToolStripSeparator());
            this.menu.Items.Add(exitItem);

            this.notifyIcon = new NotifyIcon();
            this.notifyIcon.Icon = CreateTrayIcon();
            this.notifyIcon.Text = "扫码枪接收器";
            this.notifyIcon.ContextMenuStrip = this.menu;
            this.notifyIcon.DoubleClick += this.OnDoubleClick;
            this.notifyIcon.Visible = true;
        }

        private void StartServer()
        {
            HttpServer created = null;
            try
            {
                created = new HttpServer(this.config);
                created.Notification += this.OnServerNotification;
                created.Start();
                this.server = created;
                this.lastError = null;
                Log.Write("listening on 0.0.0.0:" + this.config.Port);
            }
            catch (Exception ex)
            {
                if (created != null)
                {
                    try
                    {
                        created.Stop();
                    }
                    catch
                    {
                    }
                }
                this.lastError = ex.Message;
                Log.Write("start failed: " + ex.Message);
                MessageBox.Show(
                    "接收器启动失败：\r\n" + ex.Message +
                    "\r\n\r\n请修改 PcReceiver.ini 中的端口，然后在托盘菜单选择「重新加载配置」。",
                    "扫码枪接收器",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Warning);
            }

            if (this.server != null && this.server.IsRunning)
            {
                this.StartDiscovery();
            }
            this.UpdateTooltip();
        }

        private void StartDiscovery()
        {
            try
            {
                this.discovery = new DiscoveryResponder(Program.DiscoveryPort, this.config.Port);
                this.discovery.Start();
            }
            catch (Exception ex)
            {
                this.discovery = null;
                Log.Write("discovery start failed: " + ex.Message);
                this.Notify("自动发现未启动（UDP 端口 " + Program.DiscoveryPort + " 不可用），可在手机上手动输入地址。");
            }
        }

        private void StopDiscovery()
        {
            if (this.discovery != null)
            {
                this.discovery.Stop();
                this.discovery = null;
            }
        }

        private void ShowStartupBalloon()
        {
            string ip = PickLanIp();
            if (this.server != null && this.server.IsRunning)
            {
                string body;
                if (ip == null)
                {
                    body = "未找到局域网 IP，请检查电脑网络连接。";
                }
                else
                {
                    body = "地址：" + BuildUrl(ip) +
                           "\r\n手机 App 会自动搜索本机，也可手动填写此地址。";
                }
                this.notifyIcon.ShowBalloonTip(6000, "扫码枪接收器已启动", body, ToolTipIcon.Info);
            }
            else
            {
                this.notifyIcon.ShowBalloonTip(
                    6000,
                    "扫码枪接收器未启动",
                    this.lastError == null ? "请检查配置后重试。" : this.lastError,
                    ToolTipIcon.Error);
            }
        }

        private void UpdateTooltip()
        {
            string text;
            if (this.server != null && this.server.IsRunning)
            {
                string ip = PickLanIp();
                text = ip == null
                    ? "扫码枪接收器 · 运行中 · 未找到局域网 IP"
                    : "扫码枪接收器 · 运行中 · " + ip + ":" + this.config.Port;
            }
            else
            {
                text = "扫码枪接收器 · 未运行";
            }
            if (text.Length > 60) text = text.Substring(0, 60);
            this.notifyIcon.Text = text;
        }

        private void OnMenuOpening(object sender, CancelEventArgs e)
        {
            string ip = PickLanIp();
            this.addressItem.Text = ip == null ? "地址：未找到局域网 IP" : "地址：" + BuildUrl(ip);
            this.addressItem.Enabled = ip != null;
            if (this.server != null && this.server.IsRunning)
            {
                this.statusItem.Text = "状态：运行中 · 端口 " + this.config.Port;
            }
            else
            {
                this.statusItem.Text = "状态：未运行" +
                    (string.IsNullOrEmpty(this.lastError) ? string.Empty : " · 启动失败");
            }
        }

        private void OnCopyAddress(object sender, EventArgs e)
        {
            string ip = PickLanIp();
            if (ip == null)
            {
                this.Notify("未找到局域网 IP，请检查网络连接。");
                return;
            }
            this.CopyText(BuildUrl(ip), "地址已复制：" + BuildUrl(ip));
        }

        private void CopyText(string text, string balloonText)
        {
            try
            {
                Clipboard.SetText(text);
                this.notifyIcon.ShowBalloonTip(1500, "扫码枪接收器", balloonText, ToolTipIcon.None);
            }
            catch (Exception ex)
            {
                Log.Write("clipboard failed: " + ex.GetType().Name);
                this.Notify("复制失败，请手动在配置文件中查看。");
            }
        }

        private void OnDoubleClick(object sender, EventArgs e)
        {
            string ip = PickLanIp();
            string body;
            if (ip == null)
            {
                body = "未找到局域网 IP，请检查网络连接。";
            }
            else
            {
                body = "地址：" + BuildUrl(ip);
            }
            this.notifyIcon.ShowBalloonTip(4000, "扫码枪接收器", body, ToolTipIcon.Info);
        }

        private void OnReload(object sender, EventArgs e)
        {
            ReceiverConfig fresh;
            try
            {
                fresh = ReceiverConfig.LoadOrCreate(this.configPath);
            }
            catch (Exception ex)
            {
                Log.Write("reload failed: " + ex.Message);
                MessageBox.Show(
                    "读取配置失败：\r\n" + ex.Message,
                    "扫码枪接收器",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Warning);
                return;
            }

            this.StopDiscovery();
            if (this.server != null)
            {
                this.server.Stop();
                this.server.Notification -= this.OnServerNotification;
                this.server = null;
            }
            this.config = fresh;
            this.StartServer();
            Log.Write("config reloaded, port=" + this.config.Port);
            if (this.server != null && this.server.IsRunning)
            {
                this.notifyIcon.ShowBalloonTip(
                    2500,
                    "扫码枪接收器",
                    "配置已重新加载 · 端口 " + this.config.Port,
                    ToolTipIcon.Info);
            }
        }

        private void OnOpenConfig(object sender, EventArgs e)
        {
            try
            {
                Process.Start("notepad.exe", "\"" + this.configPath + "\"");
            }
            catch (Exception ex)
            {
                Log.Write("open config failed: " + ex.GetType().Name);
                this.Notify("无法打开配置文件，请手动定位到 " + this.configPath);
            }
        }

        private void OnOpenLog(object sender, EventArgs e)
        {
            try
            {
                string logPath = Path.Combine(Application.StartupPath, "PcReceiver.log");
                if (!File.Exists(logPath)) Log.Write("log opened from tray");
                Process.Start("notepad.exe", "\"" + logPath + "\"");
            }
            catch (Exception ex)
            {
                Log.Write("open log failed: " + ex.GetType().Name);
                this.Notify("无法打开日志文件。");
            }
        }

        private void OnServerNotification(string message)
        {
            Log.Write("notify: " + message);
            this.PostUi(delegate
            {
                try
                {
                    this.notifyIcon.ShowBalloonTip(5000, "扫码枪接收器", message, ToolTipIcon.Warning);
                }
                catch
                {
                }
            });
        }

        private void Notify(string message)
        {
            this.PostUi(delegate
            {
                try
                {
                    this.notifyIcon.ShowBalloonTip(4000, "扫码枪接收器", message, ToolTipIcon.Warning);
                }
                catch
                {
                }
            });
        }

        private void PostUi(Action action)
        {
            try
            {
                this.hiddenForm.BeginInvoke(action);
            }
            catch
            {
                try
                {
                    action();
                }
                catch
                {
                }
            }
        }

        private void OnExit(object sender, EventArgs e)
        {
            this.StopDiscovery();
            if (this.server != null)
            {
                this.server.Stop();
                this.server.Notification -= this.OnServerNotification;
                this.server = null;
            }
            if (this.notifyIcon != null)
            {
                this.notifyIcon.Visible = false;
                this.notifyIcon.Dispose();
                this.notifyIcon = null;
            }
            try
            {
                this.hiddenForm.Dispose();
            }
            catch
            {
            }
            Log.Write("receiver exited");
            this.ExitThread();
        }

        private string BuildUrl(string ip)
        {
            return "http://" + ip + ":" + this.config.Port;
        }

        private static string PickLanIp()
        {
            List<string> addresses = GetLanIpv4Addresses();
            for (int i = 0; i < addresses.Count; i++)
            {
                if (IsPrivateIp(addresses[i])) return addresses[i];
            }
            if (addresses.Count > 0) return addresses[0];
            return null;
        }

        private static List<string> GetLanIpv4Addresses()
        {
            List<string> result = new List<string>();
            try
            {
                NetworkInterface[] interfaces = NetworkInterface.GetAllNetworkInterfaces();
                foreach (NetworkInterface adapter in interfaces)
                {
                    if (adapter.OperationalStatus != OperationalStatus.Up) continue;
                    if (adapter.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                    IPInterfaceProperties properties = adapter.GetIPProperties();
                    foreach (UnicastIPAddressInformation address in properties.UnicastAddresses)
                    {
                        if (address.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                        string ip = address.Address.ToString();
                        if (ip.StartsWith("127.") || ip.StartsWith("169.254.")) continue;
                        if (!result.Contains(ip)) result.Add(ip);
                    }
                }
            }
            catch
            {
            }
            return result;
        }

        private static bool IsPrivateIp(string ip)
        {
            string[] parts = ip.Split('.');
            if (parts.Length != 4) return false;
            if (parts[0] == "10") return true;
            if (parts[0] == "192" && parts[1] == "168") return true;
            if (parts[0] == "172")
            {
                int second;
                if (!int.TryParse(parts[1], out second)) return false;
                if (second >= 16 && second <= 31) return true;
            }
            return false;
        }

        private static Icon CreateTrayIcon()
        {
            Bitmap bitmap = new Bitmap(32, 32);
            using (Graphics graphics = Graphics.FromImage(bitmap))
            {
                graphics.Clear(Color.White);
                using (Brush barBrush = new SolidBrush(Color.FromArgb(28, 28, 28)))
                {
                    int[] widths = new int[] { 2, 1, 3, 1, 2, 2, 1, 3, 1, 2, 1, 1, 3, 1, 2 };
                    int x = 3;
                    for (int i = 0; i < widths.Length && x < 29; i++)
                    {
                        graphics.FillRectangle(barBrush, x, 5, widths[i], 22);
                        x += widths[i] + 1;
                    }
                }
            }
            IntPtr handle = bitmap.GetHicon();
            bitmap.Dispose();
            using (Icon temporary = Icon.FromHandle(handle))
            {
                Icon result = (Icon)temporary.Clone();
                DestroyIcon(handle);
                return result;
            }
        }

        [DllImport("user32.dll")]
        private static extern bool DestroyIcon(IntPtr handle);
    }

    internal sealed class Job
    {
        public string[] Codes;
        public string BatchId;
        public int StartDelayMs;
        public int CharDelayMs;
        public int EnterDelayMs;
    }

    internal sealed class TypeRequest
    {
        public string batch_id { get; set; }

        public string mode { get; set; }

        public string[] items { get; set; }
    }

    internal sealed class HttpServer
    {
        private const int MaxHeaderBytes = 8192;
        private const int MaxBodyBytes = 65536;
        private const int MaxCodeLength = 512;
        private const int INPUT_KEYBOARD = 1;
        private const int KEYEVENTF_KEYUP = 0x0002;
        private const int KEYEVENTF_UNICODE = 0x0004;
        private const ushort VK_RETURN = 0x0D;

        private readonly ReceiverConfig config;
        private readonly BlockingCollection<Job> queue = new BlockingCollection<Job>();
        private TcpListener listener;
        private Thread acceptThread;
        private Thread workerThread;
        private volatile bool running;
        // 进程级互斥：重载配置后旧实例仍在输入时，新实例必须返回 409 而不是并发输入。
        private static int typingBusy;

        public event Action<string> Notification;

        public HttpServer(ReceiverConfig config)
        {
            this.config = config;
        }

        public bool IsRunning
        {
            get { return this.running; }
        }

        public void Start()
        {
            TcpListener created = new TcpListener(IPAddress.Any, this.config.Port);
            try
            {
                created.Start(16);
            }
            catch (SocketException ex)
            {
                try
                {
                    created.Stop();
                }
                catch
                {
                }
                throw new InvalidOperationException("端口 " + this.config.Port + " 无法监听：" + ex.Message, ex);
            }

            this.listener = created;
            this.running = true;

            this.acceptThread = new Thread(this.AcceptLoop);
            this.acceptThread.IsBackground = true;
            this.acceptThread.Name = "http-accept";
            this.acceptThread.Start();

            this.workerThread = new Thread(this.TypeLoop);
            this.workerThread.IsBackground = true;
            this.workerThread.Name = "typing";
            this.workerThread.Start();

            Log.Write("server started, port=" + this.config.Port + ", max_items=" + this.config.MaxItems);
        }

        public void Stop()
        {
            if (!this.running) return;
            this.running = false;
            try
            {
                if (this.listener != null) this.listener.Stop();
            }
            catch
            {
            }
            try
            {
                this.queue.CompleteAdding();
            }
            catch
            {
            }
            Log.Write("server stopped");
        }

        private void AcceptLoop()
        {
            while (this.running)
            {
                TcpClient client = null;
                try
                {
                    client = this.listener.AcceptTcpClient();
                }
                catch (ObjectDisposedException)
                {
                    break;
                }
                catch (InvalidOperationException)
                {
                    break;
                }
                catch (SocketException)
                {
                    if (!this.running) break;
                    continue;
                }

                if (!this.running)
                {
                    try
                    {
                        client.Close();
                    }
                    catch
                    {
                    }
                    break;
                }

                try
                {
                    ThreadPool.QueueUserWorkItem(this.HandleClient, client);
                }
                catch
                {
                    try
                    {
                        client.Close();
                    }
                    catch
                    {
                    }
                }
            }
        }

        private void HandleClient(object state)
        {
            TcpClient client = (TcpClient)state;
            string remote = RemoteAddress(client);
            try
            {
                client.NoDelay = true;
                using (NetworkStream stream = client.GetStream())
                {
                    stream.ReadTimeout = 5000;
                    stream.WriteTimeout = 5000;

                    string method;
                    string path;
                    Dictionary<string, string> headers;
                    string body;
                    int errorCode;
                    bool parsed = this.ReadRequest(stream, out method, out path, out headers, out body, out errorCode);
                    if (!parsed)
                    {
                        WriteError(stream, errorCode);
                        Log.Write(remote + " rejected, status=" + errorCode);
                        return;
                    }
                    this.Route(stream, remote, method, path, headers, body);
                }
            }
            catch (IOException ex)
            {
                Log.Write(remote + " io error: " + ex.GetType().Name);
            }
            catch (Exception ex)
            {
                Log.Write(remote + " handler error: " + ex.GetType().Name);
            }
            finally
            {
                try
                {
                    client.Close();
                }
                catch
                {
                }
            }
        }

        private void Route(NetworkStream stream, string remote, string method, string path,
            Dictionary<string, string> headers, string body)
        {
            if (!this.running)
            {
                WriteError(stream, 503);
                return;
            }

            if (path == "/health")
            {
                if (method != "GET")
                {
                    WriteError(stream, 405);
                    Log.Write(remote + " " + method + " /health -> 405");
                    return;
                }
                WriteJson(stream, 200, "OK", "{\"ok\":true,\"version\":\"" + Program.Version + "\"}", null);
                return;
            }

            if (path == "/v1/type")
            {
                if (method != "POST")
                {
                    WriteError(stream, 405);
                    Log.Write(remote + " " + method + " /v1/type -> 405");
                    return;
                }
                this.HandleType(stream, remote, body);
                return;
            }

            WriteError(stream, 404);
            Log.Write(remote + " " + method + " " + SafeForLog(path, 80) + " -> 404");
        }

        private void HandleType(NetworkStream stream, string remote, string body)
        {
            TypeRequest request;
            try
            {
                JavaScriptSerializer serializer = new JavaScriptSerializer();
                request = serializer.Deserialize<TypeRequest>(body);
            }
            catch
            {
                request = null;
            }
            if (request == null)
            {
                WriteError(stream, 400);
                Log.Write(remote + " invalid json");
                return;
            }

            if (!string.IsNullOrEmpty(request.mode) && request.mode != "enter")
            {
                WriteError(stream, 400);
                Log.Write(remote + " unsupported mode");
                return;
            }

            if (request.items == null || request.items.Length == 0)
            {
                WriteError(stream, 400);
                Log.Write(remote + " empty items");
                return;
            }
            if (request.items.Length > this.config.MaxItems)
            {
                WriteError(stream, 413);
                Log.Write(remote + " too many items: " + request.items.Length);
                return;
            }

            List<string> codes = new List<string>(request.items.Length);
            foreach (string raw in request.items)
            {
                if (raw == null)
                {
                    WriteError(stream, 400);
                    Log.Write(remote + " non-string item");
                    return;
                }
                string code = raw.Trim();
                if (code.Length == 0 || code.Length > MaxCodeLength)
                {
                    WriteError(stream, 400);
                    Log.Write(remote + " invalid item length: " + code.Length);
                    return;
                }
                if (ContainsControlChar(code))
                {
                    WriteError(stream, 400);
                    Log.Write(remote + " item contains control characters");
                    return;
                }
                codes.Add(code);
            }

            string batchId = SafeForLog(request.batch_id, 36);

            if (Interlocked.CompareExchange(ref typingBusy, 1, 0) != 0)
            {
                WriteError(stream, 409);
                Log.Write(remote + " busy, rejected " + codes.Count + " item(s)");
                return;
            }

            Job job = new Job();
            job.Codes = codes.ToArray();
            job.BatchId = batchId;
            job.StartDelayMs = this.config.StartDelayMs;
            job.CharDelayMs = this.config.CharDelayMs;
            job.EnterDelayMs = this.config.EnterDelayMs;

            try
            {
                this.queue.Add(job);
            }
            catch (InvalidOperationException)
            {
                Interlocked.Exchange(ref typingBusy, 0);
                WriteError(stream, 503);
                return;
            }

            WriteJson(stream, 200, "OK", "{\"ok\":true,\"accepted\":" + codes.Count + "}", null);
            Log.Write(remote + " accepted " + codes.Count + " item(s)" + BatchSuffix(batchId));
        }

        private void TypeLoop()
        {
            while (true)
            {
                Job job;
                try
                {
                    job = this.queue.Take();
                }
                catch (InvalidOperationException)
                {
                    break;
                }

                try
                {
                    this.TypeJob(job);
                }
                catch (Exception ex)
                {
                    Log.Write("typing error: " + ex.GetType().Name + " " + ex.Message);
                    this.Notify("输入过程异常，请查看日志。");
                }
                finally
                {
                    Interlocked.Exchange(ref typingBusy, 0);
                }
            }
        }

        private void TypeJob(Job job)
        {
            if (job.StartDelayMs > 0) Thread.Sleep(job.StartDelayMs);

            int typed = 0;
            bool inputError = false;
            int started = Environment.TickCount;
            Log.Write("typing started: " + job.Codes.Length + " item(s)" + BatchSuffix(job.BatchId));

            foreach (string code in job.Codes)
            {
                if (!TypeText(code, job.CharDelayMs))
                {
                    inputError = true;
                    break;
                }
                if (!TypeEnter())
                {
                    inputError = true;
                    break;
                }
                if (job.EnterDelayMs > 0) Thread.Sleep(job.EnterDelayMs);
                typed++;
            }

            int elapsed = Environment.TickCount - started;
            Log.Write("typing finished: " + typed + "/" + job.Codes.Length + " item(s), " + elapsed + " ms" +
                      BatchSuffix(job.BatchId));

            if (inputError)
            {
                Log.Write("SendInput failed; target may be running elevated");
                this.Notify("模拟键盘输入失败。若目标窗口以管理员身份运行，请改用管理员身份启动接收器。");
            }
        }

        private void Notify(string message)
        {
            Action<string> handler = this.Notification;
            if (handler != null) handler(message);
        }

        private bool ReadRequest(NetworkStream stream, out string method, out string path,
            out Dictionary<string, string> headers, out string body, out int errorCode)
        {
            method = null;
            path = null;
            headers = null;
            body = null;
            errorCode = 400;

            byte[] buffer = new byte[2048];
            MemoryStream headerStream = new MemoryStream();
            int headerEnd = -1;
            int totalRead = 0;

            while (true)
            {
                if (totalRead >= MaxHeaderBytes)
                {
                    errorCode = 431;
                    return false;
                }
                int read;
                try
                {
                    read = stream.Read(buffer, 0, buffer.Length);
                }
                catch (IOException)
                {
                    errorCode = 408;
                    return false;
                }
                if (read <= 0) break;

                headerStream.Write(buffer, 0, read);
                totalRead += read;
                byte[] data = headerStream.GetBuffer();
                headerEnd = FindHeaderEnd(data, totalRead);
                if (headerEnd >= 0) break;
            }

            if (headerEnd < 0) return false;

            byte[] all = headerStream.GetBuffer();
            string headerText = Encoding.ASCII.GetString(all, 0, headerEnd);
            string[] lines = headerText.Split(new string[] { "\r\n" }, StringSplitOptions.None);
            if (lines.Length == 0) return false;

            string[] requestLine = lines[0].Split(new char[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
            if (requestLine.Length < 2) return false;
            method = requestLine[0].ToUpperInvariant();
            path = requestLine[1];
            int queryIndex = path.IndexOf('?');
            if (queryIndex >= 0) path = path.Substring(0, queryIndex);
            if (path.Length == 0 || path.Length > 200) return false;

            headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            for (int i = 1; i < lines.Length; i++)
            {
                string line = lines[i];
                if (line.Length == 0) continue;
                int colon = line.IndexOf(':');
                if (colon <= 0) return false;
                string name = line.Substring(0, colon).Trim();
                string value = line.Substring(colon + 1).Trim();
                if (name.Length == 0) return false;
                headers[name] = value;
            }

            int contentLength = 0;
            string lengthText;
            if (headers.TryGetValue("content-length", out lengthText))
            {
                if (!int.TryParse(lengthText, out contentLength) || contentLength < 0)
                {
                    errorCode = 400;
                    return false;
                }
            }
            if (contentLength > MaxBodyBytes)
            {
                errorCode = 413;
                return false;
            }

            byte[] bodyBytes = new byte[contentLength];
            int bodyRead = 0;
            int leftoverStart = headerEnd + 4;
            int leftoverLength = totalRead - leftoverStart;
            if (leftoverLength > 0 && contentLength > 0)
            {
                int copy = Math.Min(leftoverLength, contentLength);
                Array.Copy(all, leftoverStart, bodyBytes, 0, copy);
                bodyRead = copy;
            }
            while (bodyRead < contentLength)
            {
                int read;
                try
                {
                    read = stream.Read(bodyBytes, bodyRead, contentLength - bodyRead);
                }
                catch (IOException)
                {
                    errorCode = 408;
                    return false;
                }
                if (read <= 0)
                {
                    errorCode = 400;
                    return false;
                }
                bodyRead += read;
            }

            body = Encoding.UTF8.GetString(bodyBytes);
            return true;
        }

        private static int FindHeaderEnd(byte[] data, int length)
        {
            for (int i = 0; i + 3 < length; i++)
            {
                if (data[i] == 13 && data[i + 1] == 10 && data[i + 2] == 13 && data[i + 3] == 10)
                {
                    return i;
                }
            }
            return -1;
        }

        private static string RemoteAddress(TcpClient client)
        {
            try
            {
                IPEndPoint endPoint = client.Client.RemoteEndPoint as IPEndPoint;
                if (endPoint != null) return endPoint.Address.ToString();
            }
            catch
            {
            }
            return "?";
        }

        private static bool ContainsControlChar(string text)
        {
            for (int i = 0; i < text.Length; i++)
            {
                char c = text[i];
                if (c < 0x20 || c == 0x7F) return true;
            }
            return false;
        }

        private static string SafeForLog(string text, int maxLength)
        {
            if (string.IsNullOrEmpty(text)) return string.Empty;
            StringBuilder safe = new StringBuilder(Math.Min(text.Length, maxLength));
            for (int i = 0; i < text.Length && safe.Length < maxLength; i++)
            {
                char c = text[i];
                safe.Append(char.IsControl(c) ? '?' : c);
            }
            return safe.ToString();
        }

        private static string BatchSuffix(string batchId)
        {
            return string.IsNullOrEmpty(batchId) ? string.Empty : " batch=" + batchId;
        }

        private static void WriteJson(NetworkStream stream, int status, string reason, string json, string extraHeaders)
        {
            byte[] body = Encoding.UTF8.GetBytes(json);
            StringBuilder head = new StringBuilder(160);
            head.Append("HTTP/1.1 ").Append(status).Append(' ').Append(reason).Append("\r\n");
            head.Append("Content-Type: application/json; charset=utf-8\r\n");
            head.Append("Content-Length: ").Append(body.Length).Append("\r\n");
            head.Append("Cache-Control: no-store\r\n");
            head.Append("Connection: close\r\n");
            if (!string.IsNullOrEmpty(extraHeaders)) head.Append(extraHeaders);
            head.Append("\r\n");

            byte[] headBytes = Encoding.ASCII.GetBytes(head.ToString());
            stream.Write(headBytes, 0, headBytes.Length);
            if (body.Length > 0) stream.Write(body, 0, body.Length);
            stream.Flush();
        }

        private static void WriteError(NetworkStream stream, int code)
        {
            string reason;
            string error;
            string extra = null;
            switch (code)
            {
                case 400:
                    reason = "Bad Request";
                    error = "bad_request";
                    break;
                case 404:
                    reason = "Not Found";
                    error = "not_found";
                    break;
                case 405:
                    reason = "Method Not Allowed";
                    error = "method_not_allowed";
                    extra = "Allow: GET, POST\r\n";
                    break;
                case 408:
                    reason = "Request Timeout";
                    error = "timeout";
                    break;
                case 409:
                    reason = "Conflict";
                    error = "busy";
                    break;
                case 413:
                    reason = "Payload Too Large";
                    error = "too_large";
                    break;
                case 431:
                    reason = "Request Header Fields Too Large";
                    error = "headers_too_large";
                    break;
                case 503:
                    reason = "Service Unavailable";
                    error = "unavailable";
                    break;
                default:
                    code = 500;
                    reason = "Internal Server Error";
                    error = "internal_error";
                    break;
            }
            WriteJson(stream, code, reason, "{\"ok\":false,\"error\":\"" + error + "\"}", extra);
        }

        private static bool TypeText(string text, int charDelayMs)
        {
            for (int i = 0; i < text.Length; i++)
            {
                if (!SendUnicodeChar(text[i])) return false;
                if (charDelayMs > 0) Thread.Sleep(charDelayMs);
            }
            return true;
        }

        private static bool SendUnicodeChar(char character)
        {
            INPUT[] inputs = new INPUT[2];
            inputs[0].type = INPUT_KEYBOARD;
            inputs[0].u.ki.wVk = 0;
            inputs[0].u.ki.wScan = (ushort)character;
            inputs[0].u.ki.dwFlags = KEYEVENTF_UNICODE;

            inputs[1].type = INPUT_KEYBOARD;
            inputs[1].u.ki.wVk = 0;
            inputs[1].u.ki.wScan = (ushort)character;
            inputs[1].u.ki.dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP;

            uint sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf(typeof(INPUT)));
            return sent == (uint)inputs.Length;
        }

        private static bool TypeEnter()
        {
            INPUT[] inputs = new INPUT[2];
            inputs[0].type = INPUT_KEYBOARD;
            inputs[0].u.ki.wVk = VK_RETURN;
            inputs[0].u.ki.wScan = 0x1C;
            inputs[0].u.ki.dwFlags = 0;

            inputs[1].type = INPUT_KEYBOARD;
            inputs[1].u.ki.wVk = VK_RETURN;
            inputs[1].u.ki.wScan = 0x1C;
            inputs[1].u.ki.dwFlags = KEYEVENTF_KEYUP;

            uint sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf(typeof(INPUT)));
            return sent == (uint)inputs.Length;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct INPUT
        {
            public int type;
            public INPUTUNION u;
        }

        [StructLayout(LayoutKind.Explicit)]
        private struct INPUTUNION
        {
            [FieldOffset(0)] public MOUSEINPUT mi;
            [FieldOffset(0)] public KEYBDINPUT ki;
            [FieldOffset(0)] public HARDWAREINPUT hi;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct MOUSEINPUT
        {
            public int dx;
            public int dy;
            public int mouseData;
            public int dwFlags;
            public int time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct KEYBDINPUT
        {
            public ushort wVk;
            public ushort wScan;
            public int dwFlags;
            public int time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct HARDWAREINPUT
        {
            public int uMsg;
            public ushort wParamL;
            public ushort wParamH;
        }

        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);
    }
}
