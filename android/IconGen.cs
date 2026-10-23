using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;

public static class IconGen
{
    public static void Main(string[] args)
    {
        string baseDir = args.Length > 0 ? args[0] : ".";
        string[] dirs = { "mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi" };
        int[] pxs = { 48, 72, 96, 144, 192 };
        for (int i = 0; i < dirs.Length; i++)
        {
            string dir = dirs[i];
            int px = pxs[i];
            string outDir = Path.Combine(baseDir, dir);
            Directory.CreateDirectory(outDir);
            using (var bmp = new Bitmap(px, px))
            using (var g = Graphics.FromImage(bmp))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                int r = (int)(px * 0.22);
                using (var path = RoundedRect(0, 0, px, px, r))
                using (var brush = new LinearGradientBrush(
                    new Rectangle(0, 0, px, px),
                    Color.FromArgb(255, 13, 71, 161),
                    Color.FromArgb(255, 30, 136, 229),
                    45f))
                {
                    g.FillPath(brush, path);
                }
                float cx = px / 2f;
                float cy = px / 2f;
                using (var pen = new Pen(Color.White, px * 0.085f))
                {
                    pen.StartCap = LineCap.Round;
                    pen.EndCap = LineCap.Round;
                    g.DrawArc(pen, cx - px * 0.20f, cy - px * 0.12f,
                        px * 0.40f, px * 0.42f, 200f, 140f);
                }
                using (var pen2 = new Pen(Color.White, px * 0.075f))
                {
                    pen2.StartCap = LineCap.Round;
                    pen2.EndCap = LineCap.Round;
                    g.DrawLine(pen2, cx - px * 0.06f, cy - px * 0.16f,
                                     cx - px * 0.20f, cy - px * 0.30f);
                    g.DrawLine(pen2, cx + px * 0.06f, cy - px * 0.16f,
                                     cx + px * 0.20f, cy - px * 0.30f);
                }
                using (var eye = new SolidBrush(Color.White))
                {
                    float e = px * 0.075f;
                    g.FillEllipse(eye, cx - e / 2, cy + px * 0.10f, e, e);
                }
                bmp.Save(Path.Combine(outDir, "ic_launcher.png"), ImageFormat.Png);
            }
            Console.WriteLine("  " + dir + "/ic_launcher.png (" + px + "x" + px + ")");
        }
        Console.WriteLine("图标生成完成");
    }

    static GraphicsPath RoundedRect(int x, int y, int w, int h, int r)
    {
        var p = new GraphicsPath();
        int d = r * 2;
        p.AddArc(x, y, d, d, 180, 90);
        p.AddArc(x + w - d - 1, y, d, d, 270, 90);
        p.AddArc(x + w - d - 1, y + h - d - 1, d, d, 0, 90);
        p.AddArc(x, y + h - d - 1, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }
}