"""使用 Python 标准库校验并打包 KernelSU 开发示例。"""

from datetime import datetime
from hashlib import sha256
from pathlib import Path
import re
import zipfile


ROOT = Path(__file__).resolve().parent
MODULE = ROOT / "module"
MODULE_FILES = (
    "module.prop", "skip_mount", "customize.sh", "service.sh",
    "action.sh", "scripts/info.sh",
)


def main():
    logs = []
    for name in MODULE_FILES:
        data = (MODULE / name).read_bytes()
        data.decode("utf-8")
        if b"\r" in data or data.startswith(b"\xef\xbb\xbf"):
            raise ValueError(f"{name} 必须使用 UTF-8 无 BOM 编码和 LF 换行")
    logs.append("通过：模块文件齐全，UTF-8 无 BOM、LF 换行检查。")

    props = dict(
        line.split("=", 1)
        for line in (MODULE / "module.prop").read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#")
    )
    for field in ("id", "name", "version", "versionCode", "author", "description"):
        if not props.get(field):
            raise ValueError(f"module.prop 缺少字段：{field}")
    if not re.fullmatch(r"[a-zA-Z][a-zA-Z0-9._-]+", props["id"]):
        raise ValueError("模块 ID 不符合 KernelSU 规范")
    if not props["versionCode"].isdigit():
        raise ValueError("versionCode 必须是非负整数")
    logs.append("通过：module.prop 必需字段、模块 ID 和 versionCode 检查。")

    now = datetime.now().astimezone()
    delivery = ROOT / "交付文件" / (
        now.strftime("%Y%m%d_%H%M%S_%f") + "_test_KernelSU开发骨架"
    )
    delivery.mkdir(parents=True, exist_ok=False)
    package = delivery / "test_ksu_dev_starter.zip"
    with zipfile.ZipFile(package, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in MODULE_FILES:
            entry = zipfile.ZipInfo(name, now.timetuple()[:6])
            entry.create_system = 3
            mode = 0o100755 if name.endswith(".sh") else 0o100644
            entry.external_attr = mode << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, (MODULE / name).read_bytes())
    with zipfile.ZipFile(package) as archive:
        if archive.testzip() is not None or set(archive.namelist()) != set(MODULE_FILES):
            raise ValueError("安装包完整性校验失败")
        if archive.read("module.prop") != (MODULE / "module.prop").read_bytes():
            raise ValueError("安装包元数据与源码不一致")
    logs.append("通过：ZIP 完整性检查，module.prop 位于 ZIP 根目录。")

    snapshot = delivery / "源码快照.zip"
    with zipfile.ZipFile(snapshot, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in ("README.md", "build.py", ".gitignore", ".gitattributes"):
            archive.write(ROOT / name, name)
        for name in MODULE_FILES:
            archive.write(MODULE / name, "module/" + name)

    (delivery / "构建日志.txt").write_text(
        "\n".join(logs) + "\n", encoding="utf-8"
    )
    (delivery / "交付说明.md").write_text(
        f"# KernelSU 开发骨架 {props['version']}\n\n"
        f"交付时间：{now.isoformat(timespec='seconds')}\n\n"
        "本次新增模块元数据、安装入口、单次启动环境记录、操作按钮和打包脚本。"
        "这是一份测试骨架，尚未实现用户的目标功能。\n\n"
        "构建方式：在项目目录执行 `python build.py`，无需编译器或第三方 Python 库。"
        "本次自动校验结果见构建日志.txt；自动校验不包含 Android 执行或 Shell 语法检查。\n\n"
        "安装文件：test_ksu_dev_starter.zip。将其传到手机，在 KernelSU 管理器的模块页面"
        "从本地选择安装，重启后点击模块的操作按钮。不要选择源码快照.zip。\n\n"
        "运行日志：/data/adb/modules/ksu_dev_starter/runtime/startup.log。"
        "每次开机覆盖一次；日志随模块卸载删除。无需烧录地址或外部资源。\n\n"
        "目标环境（用户提供）：OPPO Find X8s、Android 15、原版 KernelSU，"
        "版本显示为 32601-2，通过修补 init_boot 安装。\n\n"
        "已知限制：尚未连接手机进行安装、启动、按钮和卸载验证。"
        "脚本只读取基础环境并写入模块自身的日志，不挂载 system。"
        "元模块不是此骨架的依赖；老版本管理器的操作按钮支持需要实机确认。\n",
        encoding="utf-8",
    )
    checksums = [
        f"{sha256(path.read_bytes()).hexdigest()}  {path.name}"
        for path in sorted(delivery.iterdir()) if path.is_file()
    ]
    (delivery / "SHA256SUMS.txt").write_text(
        "\n".join(checksums) + "\n", encoding="utf-8"
    )
    print("\n".join(logs))
    print(f"交付目录：{delivery}")


if __name__ == "__main__":
    main()
