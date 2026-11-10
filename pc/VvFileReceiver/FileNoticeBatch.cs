using System;
using System.Collections.Generic;
using System.Text;
using System.Windows.Forms;

namespace VvFileReceiver
{
    /// <summary>
    /// 收文件的通知：批量合并 + 冷却时间。
    ///
    /// 一次拖一个文件夹过来可能是几百个文件，每个都弹一条横幅会刷屏。
    /// 规则：
    /// - 第一个文件进来后等 <see cref="GatherMs"/>，这段时间内到的文件合成一条
    /// - 弹完进入 <see cref="CooldownMs"/> 冷却，冷却期内继续攒，到点再弹一条汇总
    /// - 效果：一堆文件最多每 5 秒一条，并且只有一条会写明「收到 N 个文件 / 共多大」
    /// </summary>
    internal sealed class FileNoticeBatch : IDisposable
    {
        /// <summary>最后一个文件进来后静默这么久就发通知（连续到达会不断顺延）。</summary>
        private const int GatherMs = 1200;
        /// <summary>第一个文件进来后最多等这么久，长传输也能有阶段汇总。</summary>
        private const int MaxWaitMs = 3200;
        /// <summary>同一条通知之后至少隔这么久才再弹。</summary>
        private const int CooldownMs = 5000;
        private readonly Action<string, string> _show;
        private readonly Action<Action> _dispatch;
        private readonly Timer _timer;
        private readonly object _gate = new object();
        private readonly List<string> _names = new List<string>();

        private int _count;
        private long _bytes;
        private DateTime _holdUntil = DateTime.MinValue;
        private DateTime _firstAt = DateTime.MinValue;
        private string _lastRelative = string.Empty;

        /// <param name="show">真正弹通知（在 UI 线程调用）</param>
        /// <param name="dispatch">把回调丢到 UI 线程（接收线程不是 UI 线程）</param>
        public FileNoticeBatch(Action<string, string> show, Action<Action> dispatch)
        {
            _show = show;
            _dispatch = dispatch;
            // Timer 在 UI 线程创建，Tick 也回到 UI 线程
            _timer = new Timer();
            _timer.Interval = GatherMs;
            _timer.Tick += delegate { Flush(); };
        }

        /// <summary>最近一个收到的文件（相对接收目录）；点通知时用它定位。</summary>
        public string LastRelativePath
        {
            get { lock (_gate) { return _lastRelative; } }
        }

        /// <summary>收到一个文件；可从任意线程调用。</summary>
        public void Add(string relativePath, long bytes)
        {
            lock (_gate)
            {
                if (_count == 0) _firstAt = DateTime.Now;
                _count++;
                _bytes += bytes;
                if (_names.Count < 200) _names.Add(relativePath ?? string.Empty);
                if (!string.IsNullOrEmpty(relativePath)) _lastRelative = relativePath;
            }
            _dispatch(Arm);
        }

        /// <summary>重新计时：每来一个文件就把静默窗口顺延，但整体不超过 MaxWaitMs。</summary>
        private void Arm()
        {
            DateTime now = DateTime.Now;
            int quiet = GatherMs;
            int cap = MaxWaitMs - (int)(now - _firstAt).TotalMilliseconds;
            if (cap < 200) cap = 200;
            int wait = Math.Min(quiet, cap);

            int hold = (int)(_holdUntil - now).TotalMilliseconds;
            if (hold > wait) wait = hold; // 冷却期内先攒着，到点再弹汇总

            _timer.Stop();
            _timer.Interval = wait < 100 ? 100 : wait;
            _timer.Start();
        }

        private void Flush()
        {
            _timer.Stop();
            int count;
            long bytes;
            List<string> names;
            lock (_gate)
            {
                count = _count;
                bytes = _bytes;
                names = new List<string>(_names);
                _count = 0;
                _bytes = 0;
                _names.Clear();
            }
            if (count <= 0) return;

            _holdUntil = DateTime.Now.AddMilliseconds(CooldownMs);

            string title;
            string text;
            if (count == 1)
            {
                title = "收到 1 个文件";
                text = (names.Count > 0 ? names[0] : "已保存") + "\r\n" + SizeText(bytes);
            }
            else
            {
                title = "收到 " + count + " 个文件";
                StringBuilder builder = new StringBuilder();
                builder.Append("共 ").Append(SizeText(bytes));
                if (names.Count > 0) builder.Append("\r\n最后一个：").Append(names[names.Count - 1]);
                text = builder.ToString();
            }

            Log.Write("通知：" + title + " · " + (names.Count > 0 ? names[names.Count - 1] : "-"));
            try { _show(title, text); }
            catch { }
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
            return value.ToString(value >= 100 ? "0" : "0.0") + " " + units[unit];
        }

        public void Dispose()
        {
            try
            {
                _timer.Stop();
                _timer.Dispose();
            }
            catch { }
        }
    }
}


