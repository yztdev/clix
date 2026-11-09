# -*- coding: utf-8 -*-
"""Tiny sequence-diagram renderer: builds primitives, outputs PNG (2x) + SVG."""

import os
from PIL import Image, ImageDraw, ImageFont

FONT_REG = r"C:\Windows\Fonts\msyh.ttc"
FONT_BOLD = r"C:\Windows\Fonts\msyhbd.ttc"
SCALE = 2

_font_cache = {}


def font(size, bold=False):
    key = (size, bold)
    if key not in _font_cache:
        path = FONT_BOLD if bold else FONT_REG
        _font_cache[key] = ImageFont.truetype(path, int(size * SCALE))
    return _font_cache[key]


def text_w(s, size, bold=False):
    return font(size, bold).getlength(s) / SCALE


def wrap(text, max_w, size, bold=False):
    out = []
    for para in text.split("\n"):
        if not para:
            out.append("")
            continue
        line = ""
        for ch in para:
            trial = line + ch
            if text_w(trial, size, bold) > max_w and line:
                out.append(line)
                line = ch
            else:
                line = trial
        out.append(line)
    return out


class Seq:
    def __init__(self, title, subtitle, participants, width=1520,
                 bg="#ffffff", ink="#1f2a37", muted="#5b6b7f"):
        self.title = title
        self.subtitle = subtitle
        self.participants = participants          # [(key, label, sub)]
        self.W = width
        self.bg, self.ink, self.muted = bg, ink, muted
        self.prims = []
        self.x = {}
        self.boxes = []
        left, right = 100, width - 100
        step = (right - left) / (len(participants) - 1)
        for i, (key, label, sub) in enumerate(participants):
            self.x[key] = left + i * step
        self.y = 190
        self.frames = []
        self.frame_stack = []
        self.legend = []

    # ---------- primitives ----------
    def line(self, x1, y1, x2, y2, color, w=1.6, dash=None, arrow=None):
        self.prims.append(dict(t="line", x1=x1, y1=y1, x2=x2, y2=y2,
                               color=color, w=w, dash=dash, arrow=arrow))

    def rect(self, x, y, w, h, fill="none", stroke="none", sw=1.4, dash=None, r=0):
        self.prims.append(dict(t="rect", x=x, y=y, w=w, h=h, fill=fill,
                               stroke=stroke, sw=sw, dash=dash, r=r))

    def text(self, x, y, s, size=13.5, color=None, anchor="start", bold=False):
        self.prims.append(dict(t="text", x=x, y=y, s=s, size=size,
                               color=color or self.ink, anchor=anchor, bold=bold))

    # ---------- layout helpers ----------
    def _arrow_head(self, x1, x2, y, color, filled=True, size=7):
        d = 1 if x2 > x1 else -1
        pts = [(x2, y), (x2 - d * size, y - size * 0.55), (x2 - d * size, y + size * 0.55)]
        self.prims.append(dict(t="poly", pts=pts, fill=color if filled else "#ffffff",
                               stroke=color, sw=1.4))

    def msg(self, a, b, text, style="sync"):
        x1, x2 = self.x[a], self.x[b]
        colors = {"sync": "#33465c", "call": "#1f6feb", "return": "#64748b",
                  "cancel": "#c0392b", "async": "#7c3aed"}
        color = colors.get(style, "#33465c")
        lines = wrap(text, 430, 13.5)
        top = self.y
        for i, ln in enumerate(lines):
            self.text((x1 + x2) / 2, top + 14 + i * 20, ln, 13.5, color, "middle")
        arrow_y = top + 14 + 20 * (len(lines) - 1) + 9
        dash = [5, 4] if style == "return" else None
        start = x1 + (6 if x2 > x1 else -6)
        end = x2 - (9 if x2 > x1 else -9)
        self.line(start, arrow_y, end, arrow_y, color, 1.7 if style != "return" else 1.4, dash)
        self._arrow_head(x1, x2, arrow_y, color, filled=(style != "return"))
        self.y = arrow_y + 28
        return self

    def self_msg(self, a, text, style="sync"):
        x = self.x[a]
        col = {"sync": "#33465c", "cancel": "#c0392b"}.get(style, "#33465c")
        top = self.y
        lines = wrap(text, 360, 13)
        loop_h = 12
        self.line(x + 4, top + 10, x + 34, top + 10, col, 1.4)
        self.line(x + 34, top + 10, x + 34, top + 10 + loop_h, col, 1.4)
        self.line(x + 34, top + 10 + loop_h, x + 8, top + 10 + loop_h, col, 1.4)
        self._arrow_head(x + 34, x + 4, top + 10 + loop_h, col, filled=True, size=6)
        for i, ln in enumerate(lines):
            self.text(x + 44, top + 16 + i * 19, ln, 13, col)
        self.y = top + 10 + loop_h + 10 + 19 * (len(lines) - 1) + 18
        return self

    def note(self, over, text, style="info"):
        styles = {
            "info": ("#eef4ff", "#7aa2e8"),
            "warn": ("#fff4e5", "#e0a33a"),
            "gap": ("#fdecea", "#d05a4c"),
            "ok": ("#eaf7ee", "#57a773"),
        }
        fill, stroke = styles.get(style, styles["info"])
        keys = [over] if isinstance(over, str) else list(over)
        xs = [self.x[k] for k in keys]
        if len(xs) == 1:
            # 单列 note：优先放右边，右边放不下就翻到左边，避免被边缘挤成竖条
            bw = 380
            bx = xs[0] + 18
            if bx + bw > self.W - 30:
                bx = xs[0] - 18 - bw
            bx = max(30, bx)
        else:
            bx = min(xs) - 46
            bw = max(xs) - min(xs) + 92
            bx = max(30, bx)
            bw = min(bw, self.W - 30 - bx)
        lines = wrap(text, bw - 28, 12.5)
        bh = 14 + 19 * len(lines) + 10
        self.rect(bx, self.y, bw, bh, fill=fill, stroke=stroke, sw=1.3, r=8)
        for i, ln in enumerate(lines):
            self.text(bx + 14, self.y + 18 + i * 19, ln, 12.5, "#39465a")
        self.y += bh + 20
        return self

    def divider(self, text):
        y = self.y + 16
        self.line(40, y, self.W - 40, y, "#d6dee9", 1.2, [4, 5])
        tw = text_w(text, 12.5, True) + 24
        self.rect(self.W / 2 - tw / 2, y - 11, tw, 22, fill=self.bg, stroke="none")
        self.text(self.W / 2, y + 4.5, text, 12.5, self.muted, "middle", True)
        self.y = y + 34
        return self

    def frame(self, title, kind="loop"):
        self.frame_stack.append(dict(title=title, start=self.y - 10, kind=kind))
        self.y += 26
        return self

    def end_frame(self):
        f = self.frame_stack.pop()
        f["end"] = self.y + 2
        self.frames.append(f)
        self.y += 8
        return self

    def alt_else(self, title):
        """在同一个 alt 框内画 else 分隔线，避免两个框叠在一起。"""
        y = self.y + 4
        self.line(30, y, self.W - 30, y, "#dcc8c8", 1.2, [5, 4])
        tw = text_w(title, 12, True) + 20
        self.rect(40, y - 11, tw, 22, fill="#ffffff", stroke="#dcc8c8", sw=1.1, r=5)
        self.text(50, y + 4.5, title, 12, "#7a4b4b", "start", True)
        self.y = y + 30
        return self

    def add_legend(self, items):
        self.legend = items
        return self

    # ---------- render ----------
    def _draw(self, d):
        def dashed_line(x1, y1, x2, y2, color, w, dash):
            import math
            dx, dy = x2 - x1, y2 - y1
            dist = math.hypot(dx, dy)
            if dist == 0:
                return
            ux, uy = dx / dist, dy / dist
            pos, on = 0.0, True
            i = 0
            while pos < dist:
                seg = dash[i % len(dash)]
                nxt = min(pos + seg, dist)
                if on:
                    d.line([x1 + ux * pos, y1 + uy * pos, x1 + ux * nxt, y1 + uy * nxt],
                           fill=color, width=max(1, int(w * SCALE)))
                pos = nxt
                on = not on
                i += 1

        S = SCALE
        for p in self.prims:
            if p["t"] == "line":
                if p["dash"]:
                    dashed_line(p["x1"] * S, p["y1"] * S, p["x2"] * S, p["y2"] * S,
                                p["color"], p["w"], p["dash"])
                else:
                    d.line([p["x1"] * S, p["y1"] * S, p["x2"] * S, p["y2"] * S],
                           fill=p["color"], width=max(1, int(p["w"] * S)))
            elif p["t"] == "rect":
                xy = [p["x"] * S, p["y"] * S, (p["x"] + p["w"]) * S, (p["y"] + p["h"]) * S]
                r = p["r"] * S
                fill = None if p["fill"] == "none" else p["fill"]
                stroke = None if p["stroke"] == "none" else p["stroke"]
                if r > 0:
                    d.rounded_rectangle(xy, radius=r, fill=fill, outline=stroke,
                                        width=max(1, int(p["sw"] * S)))
                else:
                    d.rectangle(xy, fill=fill, outline=stroke, width=max(1, int(p["sw"] * S)))
            elif p["t"] == "text":
                f = font(p["size"], p["bold"])
                anchor = {"start": "ls", "middle": "ms", "end": "rs"}[p["anchor"]]
                d.text((p["x"] * S, p["y"] * S), p["s"], font=f,
                       fill=p["color"], anchor=anchor)
            elif p["t"] == "poly":
                d.polygon([(x * S, y * S) for x, y in p["pts"]], fill=p["fill"],
                          outline=p["stroke"], width=max(1, int(p["sw"] * S)))

    def render(self):
        body_end = self.y + 20
        legend_h = 58 if self.legend else 10
        H = body_end + legend_h + 30

        S = SCALE
        img = Image.new("RGB", (int(self.W * S), int(H * S)), self.bg)
        d = ImageDraw.Draw(img)

        # 背景：先画大框，再画嵌套的小框，否则外层底色会盖住内层
        for f in sorted(self.frames, key=lambda f: (f["start"], -f["end"])):
            fill = "#f7f9fc" if f["kind"] == "loop" else "#fbf7f7"
            d.rounded_rectangle([28 * S, f["start"] * S, (self.W - 28) * S, f["end"] * S],
                                radius=10 * S, outline="#c8d3e1", width=int(1.3 * S), fill=fill)
            tw = text_w(f["title"], 12, True) + 22
            d.rounded_rectangle([40 * S, (f["start"] - 10) * S, (40 + tw) * S,
                                (f["start"] + 12) * S], radius=6 * S, fill="#e8eef7",
                                outline="#c8d3e1", width=int(1.2 * S))
            d.text((51 * S, (f["start"] + 7) * S), f["title"], font=font(12, True),
                   fill="#41536b", anchor="ls")

        # lifelines
        for key, _, _ in self.participants:
            d.line([self.x[key] * S, 152 * S, self.x[key] * S, body_end * S],
                   fill="#c9d4e2", width=int(1.2 * S))
            d.line([self.x[key] * S, 152 * S, self.x[key] * S, body_end * S],
                   fill="#c9d4e2", width=1)

        # participant boxes
        for key, label, sub in self.participants:
            w = max(text_w(label, 14.5, True), text_w(sub, 11)) + 34
            h = 52
            x = self.x[key] - w / 2
            fill = "#eef4ff" if key == "M" else "#f2f6fb"
            d.rounded_rectangle([x * S, 100 * S, (x + w) * S, (100 + h) * S],
                                radius=9 * S, fill=fill, outline="#9fb3cc", width=int(1.4 * S))
            d.text(((x + w / 2) * S, 122 * S), label, font=font(14.5, True),
                   fill="#1f3550", anchor="ms")
            if sub:
                d.text(((x + w / 2) * S, 141 * S), sub, font=font(11),
                       fill="#5f7186", anchor="ms")

        # title
        d.text((40 * S, 46 * S), self.title, font=font(22, True), fill="#16233a", anchor="ls")
        d.text((40 * S, 72 * S), self.subtitle, font=font(13), fill=self.muted, anchor="ls")

        self._draw(d)

        # legend
        if self.legend:
            ly = body_end + 26
            lx = 44
            d.text((lx * S, ly * S), "图例", font=font(12, True), fill=self.muted, anchor="ls")
            lx += 44
            for label, color, dash in self.legend:
                if dash:
                    x = lx
                    for i in range(3):
                        d.line([x * S, (ly - 4) * S, (x + 7) * S, (ly - 4) * S],
                               fill=color, width=int(1.6 * S))
                        x += 12
                    x2 = x
                else:
                    d.line([lx * S, (ly - 4) * S, (lx + 34) * S, (ly - 4) * S],
                           fill=color, width=int(1.7 * S))
                    x2 = lx + 34
                d.polygon([(x2 * S, (ly - 4) * S), ((x2 - 7) * S, (ly - 8) * S),
                           ((x2 - 7) * S, ly * S)], fill=("#ffffff" if dash else color),
                          outline=color, width=max(1, int(1.3 * S)))
                d.text(((x2 + 8) * S, ly * S), label, font=font(12), fill=self.muted, anchor="ls")
                lx = x2 + 8 + text_w(label, 12) + 26

        return img

    def render_svg(self):
        body_end = self.y + 20
        legend_h = 58 if self.legend else 10
        H = body_end + legend_h + 30
        out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.W}" height="{H}" '
               f'viewBox="0 0 {self.W} {H}" font-family="Microsoft YaHei, PingFang SC, sans-serif">',
               f'<rect width="{self.W}" height="{H}" fill="{self.bg}"/>']

        for f in sorted(self.frames, key=lambda f: (f["start"], -f["end"])):
            fill = "#f7f9fc" if f["kind"] == "loop" else "#fbf7f7"
            out.append(f'<rect x="28" y="{f["start"]}" width="{self.W-56}" height="{f["end"]-f["start"]}" '
                       f'rx="10" fill="{fill}" stroke="#c8d3e1" stroke-width="1.3"/>')
            out.append(f'<rect x="40" y="{f["start"]-10}" width="{text_w(f["title"],12,True)+22}" height="22" '
                       f'rx="6" fill="#e8eef7" stroke="#c8d3e1"/>')
            out.append(f'<text x="51" y="{f["start"]+7}" font-size="12" font-weight="bold" '
                       f'fill="#41536b">{f["title"]}</text>')

        for key, _, _ in self.participants:
            out.append(f'<line x1="{self.x[key]}" y1="152" x2="{self.x[key]}" y2="{body_end}" '
                       f'stroke="#c9d4e2" stroke-width="1.2"/>')

        for key, label, sub in self.participants:
            w = max(text_w(label, 14.5, True), text_w(sub, 11)) + 34
            x = self.x[key] - w / 2
            fill = "#eef4ff" if key == "M" else "#f2f6fb"
            out.append(f'<rect x="{x}" y="100" width="{w}" height="52" rx="9" fill="{fill}" '
                       f'stroke="#9fb3cc" stroke-width="1.4"/>')
            out.append(f'<text x="{x+w/2}" y="122" font-size="14.5" font-weight="bold" '
                       f'text-anchor="middle" fill="#1f3550">{label}</text>')
            if sub:
                out.append(f'<text x="{x+w/2}" y="141" font-size="11" text-anchor="middle" '
                           f'fill="#5f7186">{sub}</text>')

        out.append(f'<text x="40" y="46" font-size="22" font-weight="bold" fill="#16233a">{self.title}</text>')
        out.append(f'<text x="40" y="72" font-size="13" fill="{self.muted}">{self.subtitle}</text>')

        for p in self.prims:
            if p["t"] == "line":
                dash = f' stroke-dasharray="{p["dash"][0]},{p["dash"][1]}"' if p["dash"] else ""
                out.append(f'<line x1="{p["x1"]}" y1="{p["y1"]}" x2="{p["x2"]}" y2="{p["y2"]}" '
                           f'stroke="{p["color"]}" stroke-width="{p["w"]}"{dash}/>')
            elif p["t"] == "rect":
                dash = f' stroke-dasharray="{p["dash"][0]},{p["dash"][1]}"' if p["dash"] else ""
                out.append(f'<rect x="{p["x"]}" y="{p["y"]}" width="{p["w"]}" height="{p["h"]}" '
                           f'rx="{p["r"]}" fill="{p["fill"]}" stroke="{p["stroke"]}" '
                           f'stroke-width="{p["sw"]}"{dash}/>')
            elif p["t"] == "text":
                anchor = {"start": "start", "middle": "middle", "end": "end"}[p["anchor"]]
                weight = ' font-weight="bold"' if p["bold"] else ""
                out.append(f'<text x="{p["x"]}" y="{p["y"]}" font-size="{p["size"]}" '
                           f'text-anchor="{anchor}" fill="{p["color"]}"{weight}>{p["s"]}</text>')
            elif p["t"] == "poly":
                pts = " ".join(f"{x},{y}" for x, y in p["pts"])
                out.append(f'<polygon points="{pts}" fill="{p["fill"]}" stroke="{p["stroke"]}" '
                           f'stroke-width="{p["sw"]}"/>')

        if self.legend:
            ly = body_end + 26
            lx = 44
            out.append(f'<text x="{lx}" y="{ly}" font-size="12" font-weight="bold" fill="{self.muted}">图例</text>')
            lx += 44
            for label, color, dash in self.legend:
                if dash:
                    out.append(f'<line x1="{lx}" y1="{ly-4}" x2="{lx+32}" y2="{ly-4}" stroke="{color}" '
                               f'stroke-width="1.6" stroke-dasharray="5,4"/>')
                    x2 = lx + 32
                else:
                    out.append(f'<line x1="{lx}" y1="{ly-4}" x2="{lx+34}" y2="{ly-4}" stroke="{color}" stroke-width="1.7"/>')
                    x2 = lx + 34
                out.append(f'<polygon points="{x2},{ly-4} {x2-7},{ly-8} {x2-7},{ly}" '
                           f'fill="{"#ffffff" if dash else color}" stroke="{color}" stroke-width="1.3"/>')
                out.append(f'<text x="{x2+8}" y="{ly}" font-size="12" fill="{self.muted}">{label}</text>')
                lx = x2 + 8 + text_w(label, 12) + 26

        out.append("</svg>")
        return "\n".join(out)


def diagram_local(outdir):
    s = Seq(
        "clix 中断时序（一）：交互 CLI 按 ESC 取消一次任务",
        "协作式取消：全局 token + 线程 interrupt，各层在边界处主动检查；Agent 跑在独立 daemon 线程上，主线程负责监听 ESC",
        [
            ("U", "用户 / 终端", "TTY"),
            ("M", "主线程 Main", "runWithCancelSupport"),
            ("A", "Agent 线程", "clix-agent-runner"),
            ("L", "LlmClient", "OkHttp / SSE"),
            ("T", "ToolRegistry", "execute_command"),
            ("S", "SnapshotService", "side-git"),
        ],
        width=1520,
    )
    s.add_legend([("同步调用", "#1f6feb", None), ("返回", "#64748b", True),
                  ("取消动作", "#c0392b", None)])

    s.divider("触发：主线程一边监工，一边等 Agent")
    s.msg("U", "M", "输入任务并回车")
    s.self_msg("M", "CancellationContext.startRun()\n创建 token（CURRENT + LOCAL）")
    s.msg("M", "A", "executor.submit(task)  →  daemon 线程继承 LOCAL token", "call")
    s.self_msg("M", "terminal.enterRawMode()\n（保留 ISIG，Ctrl+C 仍是硬退出）")

    s.frame("loop  主线程每 150ms 轮询终端（任务运行期间）")
    s.msg("M", "U", "reader.read(50ms)：读 1 个字节")
    s.frame("alt  读到孤立 ESC（ESC[A 这类方向键不算）", "alt")
    s.self_msg("M", "token.cancel()", "cancel")
    s.msg("M", "A", "future.cancel(true)  →  interrupt()", "cancel")
    s.self_msg("M", "executor.shutdownNow()", "cancel")
    s.msg("M", "U", "立即打印「已请求取消当前任务。」并回到输入态", "cancel")
    s.self_msg("M", "finally：恢复终端属性 + CancellationContext.clear(token)")
    s.end_frame()
    s.note("M", "150ms 内没读到 ESC：future.get() 超时\n→ 继续轮询，不取消", "info")
    s.end_frame()

    s.divider("扩散：Agent 线程上的检查点")
    s.note(["A", "L"], "Agent 线程的 LOCAL 仍指向同一个 token：\ncancelled=true，interrupt 位已置位", "warn")
    s.msg("A", "L", "chat(messages, tools, listener)", "call")
    s.msg("L", "A", "SSE delta（onContentDelta / onReasoningDelta）", "return")
    s.note(["L", "A"], "CLI 没有调用 cancelInFlightCalls()：已发出的 HTTP 请求不会 abort，\n流式输出可能继续打到终端（当前实现的缺口）", "gap")
    s.self_msg("A", "每轮开头 / LLM 返回后：isCancelled() → true")
    s.msg("A", "M", "return「已取消当前任务。」", "return")

    s.divider("工具与子进程：已经跑起来的怎么停")
    s.msg("A", "T", "executeTools(batch)")
    s.note("T", "每个调用前 isCancelled() → 直接返回\n「用户取消了此次工具调用」", "info")
    s.self_msg("T", "execute_command：ProcessBuilder.start()\n→ process.waitFor(timeout)")
    s.note("T", "interrupt 打断 waitFor → InterruptedException", "warn")
    s.self_msg("T", "terminateCommandTree(进程树)\n（WSL 另杀 Linux 进程组）", "cancel")
    s.msg("T", "A", "ToolOutput.failure(「用户取消了此次工具调用」)", "return")

    s.divider("收尾")
    s.note(["T", "S"], "runTurn 的 finally 无条件执行：\npostTurnSnapshot 异步落盘；取消前的改动\n仍可在 /snapshot 看到并 /restore", "ok")
    s.note(["M", "A"], "Agent 返回后：主线程清 token、打印结果、\n状态栏回落 idle；自动记忆提取被跳过\n（仅 Plan/Team 写 run_cancelled 事件）", "info")

    img = s.render()
    img.save(os.path.join(outdir, "01-local-esc-cancel.png"))
    with open(os.path.join(outdir, "01-local-esc-cancel.svg"), "w", encoding="utf-8") as f:
        f.write(s.render_svg())


def diagram_remote(outdir):
    s = Seq(
        "clix 中断时序（二）：Remote 会话的「三道闸」取消",
        "客户端发 cancel 帧后：先关输出闸，再设 token / 中断线程，再 abort HTTP，最后 8 秒兜底终态",
        [
            ("C", "客户端", "remote client"),
            ("R", "RemoteAgentSession", "server"),
            ("G", "输出闸", "adapterGate / gate"),
            ("A", "Agent 线程", "worker pool"),
            ("L", "LlmClient", "OkHttp"),
            ("Z", "终态兜底", "scheduled 8s"),
        ],
        width=1400,
    )
    s.add_legend([("同步调用", "#1f6feb", None), ("返回", "#64748b", True),
                  ("取消动作", "#c0392b", None)])

    s.msg("C", "R", "cancel 帧（turnId, force=false）")
    s.msg("R", "G", "adapterGate.onCancel() + gate.quarantine()\n第一道：先关输出闸", "cancel")
    s.msg("R", "A", "token.cancel() + thread.interrupt()", "cancel")
    s.msg("R", "L", "model.cancelInFlightCalls()\n→ Call.cancel() + generation++", "cancel")
    s.self_msg("R", "pending futures 全部失败（「任务已取消」）", "cancel")
    s.frame("alt  force / 已返回 / 预算为 0", "alt")
    s.msg("R", "C", "terminalize()：turn.terminal（唯一终态）", "return")
    s.alt_else("else  正常等待 8 秒预算")
    s.msg("R", "Z", "schedule(terminalize, 8s)", "async")
    s.msg("Z", "R", "terminalize(turn)（幂等）", "return")
    s.msg("R", "C", "turn.terminal", "return")
    s.end_frame()
    s.note(["R", "C"], "terminalTurnIds 防止重复终态；线程池复用线程前先 Thread.interrupted()\n清掉上一轮的中断位，避免新任务一启动就被判定为已取消", "info")

    img = s.render()
    img.save(os.path.join(outdir, "02-remote-cancel-gates.png"))
    with open(os.path.join(outdir, "02-remote-cancel-gates.svg"), "w", encoding="utf-8") as f:
        f.write(s.render_svg())


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    diagram_local(here)
    diagram_remote(here)
    print("ok", here)
