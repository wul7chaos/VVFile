using System;
using System.Collections.Generic;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace VvFileReceiver
{
    /// <summary>
    /// 「发送到手机」窗口：把文件/文件夹拖进来（或点按钮选），列表里可多选再移除，
    /// 点发送就推到手机的 Download/VVFile，目录结构原样保留。
    /// </summary>
    internal sealed class SendToPhoneForm : Form
    {
        private readonly List<SendItem> _items = new List<SendItem>();
        private readonly ListView _list;
        private readonly Panel _drop;
        private readonly Label _phoneLabel;
        private readonly ProgressBar _progress;
        private readonly Label _status;
        private readonly Button _send;
        private readonly Button _clear;
        private readonly Button _remove;
        private readonly Button _find;

        private PhoneTarget _phone;
        private bool _sending;
        private static string _lastHost;

        public SendToPhoneForm()
        {
            Text = "VVFile · 发送到手机";
            StartPosition = FormStartPosition.CenterScreen;
            MinimumSize = new Size(680, 520);
            Size = new Size(760, 600);
            Font = new Font("Microsoft YaHei UI", 9f);
            Icon = AppIcon.Window;
            BackColor = Color.FromArgb(32, 32, 36);
            ForeColor = Color.Gainsboro;

            Panel top = new Panel { Dock = DockStyle.Top, Height = 44, Padding = new Padding(12, 8, 12, 4) };
            _phoneLabel = new Label
            {
                Dock = DockStyle.Fill,
                Text = "还没找手机",
                TextAlign = ContentAlignment.MiddleLeft,
                ForeColor = Color.Gainsboro,
            };
            _find = MakeButton("查找手机", 96);
            _find.Dock = DockStyle.Right;
            _find.Click += delegate { FindPhoneAsync(); };
            top.Controls.Add(_phoneLabel);
            top.Controls.Add(_find);

            _drop = new Panel
            {
                Dock = DockStyle.Top,
                Height = 104,
                Margin = new Padding(12),
                AllowDrop = true,
                BorderStyle = BorderStyle.FixedSingle,
                BackColor = Color.FromArgb(42, 42, 48),
            };
            Label dropHint = new Label
            {
                Dock = DockStyle.Fill,
                Text = "把文件或文件夹拖到这里\r\n（支持多选，文件夹会原样保留结构）\r\n或点下面的「添加文件 / 添加文件夹」",
                TextAlign = ContentAlignment.MiddleCenter,
                ForeColor = Color.DarkGray,
            };
            _drop.Controls.Add(dropHint);
            _drop.DragEnter += OnDragEnter;
            _drop.DragDrop += OnDragDrop;
            _drop.Click += delegate { AddFiles(); };

            Panel buttons = new Panel { Dock = DockStyle.Top, Height = 46, Padding = new Padding(12, 4, 12, 6) };
            FlowLayoutPanel flow = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight, WrapContents = false };
            Button addFiles = MakeButton("添加文件", 96);
            addFiles.Click += delegate { AddFiles(); };
            Button addFolder = MakeButton("添加文件夹", 104);
            addFolder.Click += delegate { AddFolder(); };
            _remove = MakeButton("移除所选", 96);
            _remove.Click += delegate { RemoveSelected(); };
            _clear = MakeButton("清空待发送", 104);
            _clear.Click += delegate { ClearAll(); };
            flow.Controls.Add(addFiles);
            flow.Controls.Add(addFolder);
            flow.Controls.Add(_remove);
            flow.Controls.Add(_clear);
            buttons.Controls.Add(flow);

            Panel bottom = new Panel { Dock = DockStyle.Bottom, Height = 88, Padding = new Padding(12, 6, 12, 10) };
            _status = new Label
            {
                Dock = DockStyle.Top,
                Height = 22,
                Text = "待发送 0 项",
                ForeColor = Color.DarkGray,
            };
            _progress = new ProgressBar { Dock = DockStyle.Top, Height = 16, Style = ProgressBarStyle.Continuous };
            Panel sendRow = new Panel { Dock = DockStyle.Bottom, Height = 40 };
            _send = MakeButton("发送到手机", 128);
            _send.Dock = DockStyle.Right;
            _send.Enabled = false;
            _send.Click += delegate { StartSend(); };
            sendRow.Controls.Add(_send);
            bottom.Controls.Add(sendRow);
            bottom.Controls.Add(_progress);
            bottom.Controls.Add(_status);

            _list = new ListView
            {
                Dock = DockStyle.Fill,
                View = View.Details,
                FullRowSelect = true,
                MultiSelect = true,
                HideSelection = false,
                BorderStyle = BorderStyle.None,
                BackColor = Color.FromArgb(28, 28, 32),
                ForeColor = Color.Gainsboro,
            };
            _list.Columns.Add("名称", 220);
            _list.Columns.Add("类型", 80);
            _list.Columns.Add("大小", 90, HorizontalAlignment.Right);
            _list.Columns.Add("位置", 320);
            _list.KeyDown += OnListKeyDown;

            Panel listWrap = new Panel { Dock = DockStyle.Fill, Padding = new Padding(12, 0, 12, 0) };
            listWrap.Controls.Add(_list);

            // Dock 顺序：先加 Fill 的容器，再自下而上加贴边的容器
            Controls.Add(listWrap);
            Controls.Add(bottom);
            Controls.Add(buttons);
            Controls.Add(_drop);
            Controls.Add(top);

            FormClosing += delegate(object sender, FormClosingEventArgs e)
            {
                if (_sending)
                {
                    e.Cancel = true;
                    MessageBox.Show(this, "正在发送，等这一轮传完再关窗口。", "发送到手机",
                        MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
            };
            _lastHost = LoadLastHost();
            Load += delegate { RefreshPhoneLabel(); };
        }

        /// <summary>上次连上的手机地址，存成一行小文件，下次开窗口先试它。</summary>
        private static string LastHostFile
        {
            get
            {
                string dir = Path.GetDirectoryName(Log.Path);
                return Path.Combine(dir ?? ".", "phone.txt");
            }
        }

        private static string LoadLastHost()
        {
            try
            {
                if (File.Exists(LastHostFile))
                {
                    string text = File.ReadAllText(LastHostFile).Trim();
                    if (text.Length > 0) return text;
                }
            }
            catch { }
            return null;
        }

        private static void SaveLastHost(string host)
        {
            try { File.WriteAllText(LastHostFile, host ?? string.Empty); }
            catch { }
        }

        private static Button MakeButton(string text, int width)
        {
            Button button = new Button
            {
                Text = text,
                Width = width,
                Height = 30,
                FlatStyle = FlatStyle.Flat,
                BackColor = Color.FromArgb(58, 58, 66),
                ForeColor = Color.Gainsboro,
                Margin = new Padding(0, 0, 8, 0),
            };
            button.FlatAppearance.BorderColor = Color.FromArgb(80, 80, 90);
            return button;
        }

        /// <summary>命令行/右键菜单预填：直接把这些路径放进待发送列表。</summary>
        public void Preload(System.Collections.Generic.IEnumerable<string> paths)
        {
            if (paths == null) return;
            System.Collections.Generic.List<string> list = new System.Collections.Generic.List<string>();
            foreach (string path in paths) if (!string.IsNullOrEmpty(path)) list.Add(path);
            if (list.Count > 0) AddPaths(list);
        }

        // ---------- 列表 ----------

        private void OnDragEnter(object sender, DragEventArgs e)
        {
            if (e.Data != null && e.Data.GetDataPresent(DataFormats.FileDrop))
                e.Effect = DragDropEffects.Copy;
            else
                e.Effect = DragDropEffects.None;
        }

        private void OnDragDrop(object sender, DragEventArgs e)
        {
            string[] paths = e.Data.GetData(DataFormats.FileDrop) as string[];
            if (paths != null) AddPaths(paths);
        }

        private void OnListKeyDown(object sender, KeyEventArgs e)
        {
            if (e.KeyCode == Keys.Delete)
            {
                RemoveSelected();
                e.Handled = true;
            }
        }

        private void AddFiles()
        {
            using (OpenFileDialog dialog = new OpenFileDialog())
            {
                dialog.Title = "选择要发送的文件";
                dialog.Multiselect = true;
                if (dialog.ShowDialog(this) == DialogResult.OK) AddPaths(dialog.FileNames);
            }
        }

        private void AddFolder()
        {
            using (FolderBrowserDialog dialog = new FolderBrowserDialog())
            {
                dialog.Description = "选择要发送的文件夹（整个目录会原样发过去）";
                if (dialog.ShowDialog(this) == DialogResult.OK) AddPaths(new[] { dialog.SelectedPath });
            }
        }

        private void AddPaths(IEnumerable<string> paths)
        {
            int added = 0;
            foreach (string path in paths)
            {
                if (string.IsNullOrEmpty(path)) continue;
                bool isDir = Directory.Exists(path);
                if (!isDir && !File.Exists(path)) continue;
                string full = Path.GetFullPath(path);
                bool exists = false;
                foreach (SendItem item in _items)
                {
                    if (string.Equals(item.Path, full, StringComparison.OrdinalIgnoreCase)) { exists = true; break; }
                }
                if (exists) continue;
                _items.Add(new SendItem { Path = full, IsDir = isDir });
                added++;
            }
            if (added > 0) RefreshList();
        }

        private void RemoveSelected()
        {
            if (_sending) return;
            List<string> selected = new List<string>();
            foreach (ListViewItem row in _list.SelectedItems) selected.Add((string)row.Tag);
            if (selected.Count == 0) return;
            _items.RemoveAll(delegate(SendItem item) { return selected.Contains(item.Path); });
            RefreshList();
        }

        private void ClearAll()
        {
            if (_sending) return;
            _items.Clear();
            RefreshList();
        }

        private void RefreshList()
        {
            _list.BeginUpdate();
            _list.Items.Clear();
            foreach (SendItem item in _items)
            {
                ListViewItem row = new ListViewItem(Path.GetFileName(item.Path.TrimEnd('\\', '/')));
                row.SubItems.Add(item.IsDir ? "文件夹" : "文件");
                row.SubItems.Add(item.IsDir ? FolderSize(item.Path) : SizeText(new FileInfo(item.Path).Length));
                row.SubItems.Add(item.IsDir ? item.Path : Path.GetDirectoryName(item.Path));
                row.Tag = item.Path;
                _list.Items.Add(row);
            }
            _list.EndUpdate();
            _send.Enabled = _items.Count > 0 && !_sending;
            _status.Text = "待发送 " + _items.Count + " 项" + (_sending ? "（正在发送…）" : "");
        }

        private static string FolderSize(string path)
        {
            try
            {
                long total = 0;
                foreach (string file in Directory.EnumerateFiles(path, "*", SearchOption.AllDirectories))
                {
                    try { total += new FileInfo(file).Length; }
                    catch { }
                }
                return SizeText(total);
            }
            catch
            {
                return "-";
            }
        }

        private static string SizeText(long bytes)
        {
            if (bytes < 1024) return bytes + " B";
            double value = bytes;
            string[] units = { "B", "KB", "MB", "GB", "TB" };
            int unit = 0;
            while (value >= 1024 && unit < units.Length - 1)
            {
                value /= 1024;
                unit++;
            }
            return value.ToString(value >= 100 ? "0" : "0.0", CultureInfo.InvariantCulture) + " " + units[unit];
        }

        // ---------- 找手机 ----------

        private void FindPhoneAsync()
        {
            if (_sending) return;
            _find.Enabled = false;
            _phoneLabel.Text = "正在找手机…";
            Thread thread = new Thread(delegate()
            {
                PhoneTarget hit = PhoneClient.Discover(PhoneClient.DefaultPort, 600, _lastHost);
                BeginInvoke((Action)delegate
                {
                    _find.Enabled = true;
                    _phone = hit;
                    if (hit != null) _lastHost = hit.Host;
                    RefreshPhoneLabel();
                });
            });
            thread.IsBackground = true;
            thread.Start();
        }

        private void RefreshPhoneLabel()
        {
            if (_phone == null)
            {
                _phoneLabel.Text = "还没找到手机：手机打开一次 VVFile，两边连同一个 WiFi，再点「查找手机」";
                _phoneLabel.ForeColor = Color.Gainsboro;
                return;
            }
            _phoneLabel.Text = "手机：" + _phone.Label + (_phone.Dir == null ? "" : "  →  " + _phone.Dir);
            _phoneLabel.ForeColor = Color.FromArgb(120, 200, 140);
        }

        // ---------- 发送 ----------

        private void StartSend()
        {
            if (_sending || _items.Count == 0) return;
            _sending = true;
            _send.Enabled = false;
            _find.Enabled = false;
            _clear.Enabled = false;
            _remove.Enabled = false;
            _progress.Value = 0;
            RefreshList();
            _status.Text = "准备中…";

            List<SendItem> snapshot = new List<SendItem>(_items);
            Thread thread = new Thread(delegate() { DoSend(snapshot); });
            thread.IsBackground = true;
            thread.Start();
        }

        private void DoSend(List<SendItem> items)
        {
            StringBuilder report = new StringBuilder();
            int files = 0;
            int dirs = 0;
            int failed = 0;
            try
            {
                PhoneTarget target = _phone != null ? PhoneClient.Ping(_phone) : null;
                if (target == null && !string.IsNullOrEmpty(_lastHost))
                    target = PhoneClient.Ping(new PhoneTarget { Host = _lastHost, Port = PhoneClient.DefaultPort });
                if (target == null)
                {
                    // 没连过或手机换了 IP：现场找一遍，不用先点「查找手机」
                    BeginInvoke((Action)delegate { _status.Text = "正在找手机…"; });
                    target = PhoneClient.Discover(PhoneClient.DefaultPort, 600, _lastHost);
                }
                if (target == null)
                {
                    Report("没找到手机。确认 VVFile 在手机上开着（通知栏有「正在接收电脑发来的文件」），两边连同一个 WiFi。\r\n\r\n" +
                        "另外：不少手机息屏后会限制后台联网（真机实测过），锁屏状态下可能连不上——点亮一下屏幕再点发送试试。");
                    return;
                }
                _phone = target;
                _lastHost = target.Host;
                SaveLastHost(target.Host);
                BeginInvoke((Action)RefreshPhoneLabel);

                // 先把要发的目录/文件摊平，方便算总进度
                List<Job> jobs = Expand(items, report);
                long total = 0;
                foreach (Job job in jobs) if (!job.IsDir) total += job.Size;
                long done = 0;
                int index = 0;

                foreach (Job job in jobs)
                {
                    index++;
                    if (_sending == false) break;
                    string label = job.Relative + (job.IsDir ? "/" : "");
                    BeginInvoke((Action)delegate { _status.Text = "(" + index + "/" + jobs.Count + ") " + label; });

                    if (job.IsDir)
                    {
                        string response = PhoneClient.MkDir(target, job.Relative);
                        if (!Succeeded(response)) { failed++; report.AppendLine("建目录失败：" + label); }
                        else dirs++;
                        continue;
                    }

                    long baseDone = done;
                    try
                    {
                        string response = PhoneClient.Upload(target, job.LocalPath, job.Relative, delegate(long sent)
                        {
                            long now = baseDone + sent;
                            int percent = total <= 0 ? 0 : (int)Math.Min(100, now * 100 / total);
                            BeginInvoke((Action)delegate { _progress.Value = Math.Max(0, Math.Min(100, percent)); });
                        });
                        if (Succeeded(response)) files++;
                        else
                        {
                            failed++;
                            report.AppendLine("失败：" + label + " → " + FirstLine(response));
                        }
                    }
                    catch (Exception ex)
                    {
                        failed++;
                        report.AppendLine("失败：" + label + " → " + ex.Message);
                    }
                    done = baseDone + job.Size;
                }

                string summary = string.Format("发送完成：{0} 个文件 · {1} 个文件夹", files, dirs) +
                    (failed > 0 ? "，" + failed + " 项失败" : "");
                BeginInvoke((Action)delegate
                {
                    if (failed == 0) _items.Clear();
                    RefreshList();
                    _status.Text = summary;
                    _progress.Value = failed == 0 ? 100 : _progress.Value;
                });
                if (failed > 0) Report(summary + "\r\n\r\n" + report);
            }
            catch (Exception ex)
            {
                Report("发送出错：" + ex.Message);
            }
            finally
            {
                BeginInvoke((Action)delegate
                {
                    _sending = false;
                    _send.Enabled = _items.Count > 0;
                    _find.Enabled = true;
                    _clear.Enabled = true;
                    _remove.Enabled = true;
                });
            }
        }

        private static bool Succeeded(string response)
        {
            return response != null && response.IndexOf(" 200 ", StringComparison.Ordinal) > 0;
        }

        private static string FirstLine(string text)
        {
            if (string.IsNullOrEmpty(text)) return "没有响应";
            int index = text.IndexOfAny(new[] { '\r', '\n' });
            string line = index > 0 ? text.Substring(0, index) : text;
            return line.Length > 160 ? line.Substring(0, 160) : line;
        }

        /// <summary>把列表里的条目摊成「建目录 + 上传文件」的任务序列，相对路径保持原结构。</summary>
        private static List<Job> Expand(List<SendItem> items, StringBuilder report)
        {
            List<Job> jobs = new List<Job>();
            foreach (SendItem item in items)
            {
                if (!item.IsDir)
                {
                    jobs.Add(new Job
                    {
                        LocalPath = item.Path,
                        Relative = Path.GetFileName(item.Path),
                        IsDir = false,
                        Size = SafeLength(item.Path),
                    });
                    continue;
                }

                string root = item.Path.TrimEnd('\\', '/');
                string baseName = Path.GetFileName(root);
                jobs.Add(new Job { Relative = baseName, IsDir = true });
                try
                {
                    foreach (string dir in Directory.EnumerateDirectories(root, "*", SearchOption.AllDirectories))
                    {
                        jobs.Add(new Job { Relative = RelativeOf(root, dir, baseName), IsDir = true });
                    }
                    foreach (string file in Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories))
                    {
                        jobs.Add(new Job
                        {
                            LocalPath = file,
                            Relative = RelativeOf(root, file, baseName),
                            IsDir = false,
                            Size = SafeLength(file),
                        });
                    }
                }
                catch (Exception ex)
                {
                    report.AppendLine("读目录失败：" + item.Path + " → " + ex.Message);
                }
            }
            return jobs;
        }

        private static string RelativeOf(string root, string path, string baseName)
        {
            string relative = path.Substring(root.Length).TrimStart('\\', '/');
            return (baseName + "/" + relative.Replace('\\', '/')).TrimEnd('/');
        }

        private static long SafeLength(string path)
        {
            try { return new FileInfo(path).Length; }
            catch { return 0; }
        }

        private void Report(string message)
        {
            BeginInvoke((Action)delegate
            {
                MessageBox.Show(this, message, "发送到手机", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            });
        }

        private sealed class SendItem
        {
            public string Path;
            public bool IsDir;
        }

        private sealed class Job
        {
            public string LocalPath;
            public string Relative;
            public bool IsDir;
            public long Size;
        }
    }
}





