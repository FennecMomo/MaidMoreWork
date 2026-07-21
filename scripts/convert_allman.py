#!/usr/bin/env python3
"""
maidmorework 项目 K&R → Allman 大括号风格批量转换脚本

处理规则：
1. 行末 { 移到下一行（排除已经是纯 { 的行）
2. } else { → }\nelse\n{
3. } else if (...) { → }\nelse if (...)\n{
4. } catch (...) { → }\ncatch (...)\n{
5. } finally { → }\nfinally\n{
6. } while (...) { → }\nwhile (...)\n{  (do-while)
7. try { → try\n{
8. } else (next line is {) → }\nelse
9. -> { → ->\n{  (lambda/switch arrow)
10. 不动数组初始化 new int[]{1,2}（行末不是纯 {）
11. 不动已经是 Allman 格式的行（仅含 { 的行）

用法：直接运行 python convert_allman.py
"""

import re, os

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "src", "main", "java")


def is_brace_only_line(stripped):
    """这行只有 { 以及可选的注释"""
    code_part = stripped.split("//")[0].strip()
    return code_part == "{"


def transform_file(filepath):
    with open(filepath, "r", encoding="utf-8") as f:
        lines = f.readlines()

    new_lines = []
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.rstrip()

        # ============================================================
        # 第一类：} else / } catch / } finally 拆分
        # 即使行末没有 {，也需要把 } 和 else 拆到不同行
        # ============================================================

        # } else if (...) 或 } else if (...) {
        m = re.match(r"^(\s*)\}\s*else\s+if\s*(\([^)]*\))\s*(\{?)\s*$", stripped)
        if m and not is_brace_only_line(stripped):
            indent = m.group(1)
            cond = m.group(2)
            has_brace = m.group(3) == "{"
            new_lines.append(indent + "}\n")
            if has_brace:
                new_lines.append(indent + "else if " + cond + "\n")
                new_lines.append(indent + "{\n")
            else:
                new_lines.append(indent + "else if " + cond + "\n")
            i += 1
            continue

        # } else 或 } else {
        m = re.match(r"^(\s*)\}\s*else\s*(\{?)\s*$", stripped)
        if m and not is_brace_only_line(stripped):
            indent = m.group(1)
            has_brace = m.group(2) == "{"
            new_lines.append(indent + "}\n")
            if has_brace:
                new_lines.append(indent + "else\n")
                new_lines.append(indent + "{\n")
            else:
                new_lines.append(indent + "else\n")
            i += 1
            continue

        # } catch (...) {
        m = re.match(r"^(\s*)\}\s*catch\s*(\([^)]*\))\s*\{\s*$", stripped)
        if m:
            indent = m.group(1)
            cond = m.group(2)
            new_lines.append(indent + "}\n")
            new_lines.append(indent + "catch " + cond + "\n")
            new_lines.append(indent + "{\n")
            i += 1
            continue

        # } finally {
        m = re.match(r"^(\s*)\}\s*finally\s*\{\s*$", stripped)
        if m:
            indent = m.group(1)
            new_lines.append(indent + "}\n")
            new_lines.append(indent + "finally\n")
            new_lines.append(indent + "{\n")
            i += 1
            continue

        # } while (...) {  (do-while)
        m = re.match(r"^(\s*)\}\s*while\s*(\([^)]*\))\s*\{\s*$", stripped)
        if m:
            indent = m.group(1)
            cond = m.group(2)
            new_lines.append(indent + "}\n")
            new_lines.append(indent + "while " + cond + "\n")
            new_lines.append(indent + "{\n")
            i += 1
            continue

        # ============================================================
        # 第二类：行末有 { 的情况
        # ============================================================

        if not stripped.endswith("{"):
            new_lines.append(line)
            i += 1
            continue

        # 已经是纯花括号行，不动
        if is_brace_only_line(stripped):
            new_lines.append(line)
            i += 1
            continue

        indent = line[: len(line) - len(line.lstrip())]

        # } else if (...) {  (行末有 { 的完整版)
        m = re.match(r"^(\s*\}\s*else\s+if\s*\([^)]*\)\s*)\{$", stripped)
        if m:
            before = m.group(1).rstrip()
            new_lines.append(indent + before + "\n")
            new_lines.append(indent + "{\n")
            i += 1
            continue

        # } else {
        m = re.match(r"^(\s*\}\s*else\s*)\{$", stripped)
        if m:
            before = m.group(1).strip()
            new_lines.append(indent + before + "\n")
            new_lines.append(indent + "{\n")
            i += 1
            continue

        # } catch (...) {
        m = re.match(r"^(\s*\}\s*catch\s*\([^)]*\)\s*)\{$", stripped)
        if m:
            before = m.group(1).rstrip()
            new_lines.append(indent + before + "\n")
            new_lines.append(indent + "{\n")
            i += 1
            continue

        # } finally {
        m = re.match(r"^(\s*\}\s*finally\s*)\{$", stripped)
        if m:
            before = m.group(1).strip()
            new_lines.append(indent + before + "\n")
            new_lines.append(indent + "{\n")
            i += 1
            continue

        # } while (...) {
        m = re.match(r"^(\s*\}\s*while\s*\([^)]*\)\s*)\{$", stripped)
        if m:
            before = m.group(1).rstrip()
            new_lines.append(indent + before + "\n")
            new_lines.append(indent + "{\n")
            i += 1
            continue

        # try {
        m = re.match(r"^(\s*try\s*)\{$", stripped)
        if m:
            before = m.group(1).strip()
            new_lines.append(indent + before + "\n")
            new_lines.append(indent + "{\n")
            i += 1
            continue

        # lambda / switch arrow:  -> {
        m = re.match(r"^(.*->\s*)\{$", stripped)
        if m:
            before = m.group(1).rstrip()
            new_lines.append(indent + before + "\n")
            new_lines.append(indent + "{\n")
            i += 1
            continue

        # 普通行末花括号: xxx {
        before = stripped[:-1].rstrip()
        new_lines.append(indent + before + "\n")
        new_lines.append(indent + "{\n")
        i += 1

    return new_lines


if __name__ == "__main__":
    changed = 0
    total = 0
    for root, dirs, files in os.walk(SRC):
        for f in files:
            if not f.endswith(".java"):
                continue
            total += 1
            filepath = os.path.join(root, f)
            with open(filepath, "r", encoding="utf-8") as fh:
                original = fh.readlines()

            new_lines = transform_file(filepath)

            # 比较是否有变化
            original_text = "".join(original)
            new_text = "".join(new_lines)
            if original_text != new_text:
                with open(filepath, "w", encoding="utf-8") as fh:
                    fh.write(new_text)
                changed += 1
                print(f"  [FIXED] {f}")
            else:
                print(f"  [OK]    {f}")

    print(f"\nDone: {changed}/{total} files changed.")
