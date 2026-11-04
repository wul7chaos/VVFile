using System;
using System.Drawing;
using System.IO;

namespace VvFileReceiver
{
    /// <summary>
    /// 程序图标：和手机端 VVFile 的启动图标同一套（紫底 + 白色 VV 文件夹）。
    /// ico 以嵌入资源形式带在 exe 里，托盘、窗口标题栏、任务栏都能拿到清晰的尺寸。
    /// </summary>
    internal static class AppIcon
    {
        private const string ResourceName = "VvFileReceiver.app.ico";

        private static Icon _window;
        private static Icon _tray;

        /// <summary>窗口标题栏 / 任务栏用（32×32）。</summary>
        public static Icon Window
        {
            get
            {
                if (_window == null) _window = Load(32);
                return _window;
            }
        }

        /// <summary>托盘用（跟随系统小图标尺寸，通常 16×16）。</summary>
        public static Icon Tray
        {
            get
            {
                if (_tray == null) _tray = Load(16);
                return _tray;
            }
        }

        private static Icon Load(int size)
        {
            try
            {
                using (Stream stream = typeof(AppIcon).Assembly.GetManifestResourceStream(ResourceName))
                {
                    if (stream != null)
                    {
                        byte[] bytes = new byte[stream.Length];
                        stream.Read(bytes, 0, bytes.Length);
                        using (MemoryStream memory = new MemoryStream(bytes))
                            return new Icon(memory, size, size);
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Write("加载图标失败：" + ex.Message);
            }
            // 兜底：从自身 exe 里取（资源没嵌进来时不至于空着）
            try { return Icon.ExtractAssociatedIcon(System.Windows.Forms.Application.ExecutablePath); }
            catch { return SystemIcons.Application; }
        }
    }
}
