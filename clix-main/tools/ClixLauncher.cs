// Windows launcher for the local-first clix release.
//
// A .cmd wrapper makes cmd.exe print "Terminate batch job (Y/N)?" when the user
// presses Ctrl+C, and answering Yes can kill the batch while leaving Java alive.
// This launcher starts the bundled JVM directly and keeps Ctrl+C owned by the JVM,
// so clix's own cancel handling (first press cancels, second press exits) works
// without an extra cmd prompt.
using System;
using System.Diagnostics;
using System.IO;
using System.Text;

internal static class ClixLauncher
{
    private static int Main(string[] args)
    {
        string home = Path.GetFullPath(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, ".."));
        // Optional overrides keep the launcher testable and allow a custom runtime.
        string java = Environment.GetEnvironmentVariable("CLIX_JAVA_EXE");
        if (string.IsNullOrWhiteSpace(java))
        {
            java = Path.Combine(home, "runtime", "bin", "java.exe");
        }
        string jar = Environment.GetEnvironmentVariable("CLIX_JAR");
        if (string.IsNullOrWhiteSpace(jar))
        {
            jar = Path.Combine(home, "lib", "clix.jar");
        }

        if (!File.Exists(java))
        {
            Console.Error.WriteLine("clix: bundled Java runtime not found: " + java);
            return 2;
        }
        if (!File.Exists(jar))
        {
            Console.Error.WriteLine("clix: clix.jar not found: " + jar);
            return 2;
        }

        // Keep the launcher alive on Ctrl+C; the JVM receives the same console event
        // and decides whether to cancel the turn or exit.
        Console.CancelKeyPress += delegate(object sender, ConsoleCancelEventArgs e) { e.Cancel = true; };

        ProcessStartInfo start = new ProcessStartInfo(java);
        start.UseShellExecute = false;
        start.WorkingDirectory = Environment.CurrentDirectory;
        StringBuilder command = new StringBuilder("-jar ");
        command.Append(Quote(jar));
        for (int i = 0; i < args.Length; i++)
        {
            command.Append(' ').Append(Quote(args[i]));
        }
        start.Arguments = command.ToString();

        try
        {
            using (Process process = Process.Start(start))
            {
                if (process == null)
                {
                    Console.Error.WriteLine("clix: failed to start Java");
                    return 1;
                }
                process.WaitForExit();
                return process.ExitCode;
            }
        }
        catch (Exception error)
        {
            Console.Error.WriteLine("clix: " + error.Message);
            return 1;
        }
    }

    // Minimal Windows command-line quoting for jar paths and forwarded arguments.
    private static string Quote(string value)
    {
        if (value == null)
        {
            return "\"\"";
        }
        if (value.Length > 0 && value.IndexOfAny(new char[] { ' ', '\t', '"' }) < 0)
        {
            return value;
        }
        StringBuilder result = new StringBuilder("\"");
        int backslashes = 0;
        foreach (char ch in value)
        {
            if (ch == '\\')
            {
                backslashes++;
                continue;
            }
            if (ch == '"')
            {
                result.Append('\\', backslashes * 2 + 1).Append('"');
                backslashes = 0;
                continue;
            }
            result.Append('\\', backslashes).Append(ch);
            backslashes = 0;
        }
        result.Append('\\', backslashes * 2).Append('"');
        return result.ToString();
    }
}
