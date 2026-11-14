# 渲染基础首批实现

日期：2026-11-11。对应实施方案的 T00/T01，以及 T02/T03 的首批基础；工作包完成情况以[开发进度](../development-progress.md)为准。

本文记录首批状态。后续[第二批优先调度与正式缓存](render-scheduling-and-export-cache.md)已接入部分正式导出请求；下文未接入描述不代表第二批之后仍未接入。

## 1. 已接入的行为

`EditSnapshot` 将现有可变 `EditorParams` 捕获为不可变字符串。规范 JSON 对对象键排序、保留数组顺序、统一零与数字表示，SHA-256 用于值身份；它没有重写旧 sidecar 格式。每次 `toParams()` 都生成独立适配对象。

`EngineTaskQueue` 在现有单并发引擎 dispatcher 外增加协程 Mutex。dispatcher 只限制同时运行的线程，不阻止任务切换到 Main/IO 时另一个任务进入，因此本次锁覆盖单张导出、批量和快捷导出的完整会话，包括准备、保存和恢复。普通预览、开片、配光、宽容度和释放也使用该队列。进度与取消仍走 EngineClient 已定义的线程安全旁路，不能等待该锁。

队列不允许递归获取锁。完整导出事务内部使用 dispatcher 执行 native 阶段，不能再调用 `onEngine()`。现有 generation 检查、最新请求合并、RAW 解码取消继续保留。当前没有新增优先队列、设备内存准入或后台任务存储，不将它们描述为已完成。

`ExportOptions` 在任务提交时固定格式、尺寸、色域、JPEG 质量、拍摄/GPS 选项和镜头校正开关。单张与快捷导出冻结编辑；批量先固定已存在的各图编辑，缺省智能编辑在首个输出之前解析为快照。保存函数使用提交的 options，不再从界面实时读取这些选项。

真实回归用例在修复前失败：开始 TIFF 导出后立即把界面格式改成 PNG，最终输出跟随了后来的 PNG。修复后该用例及批量侧文件修改用例通过。实际产品界面不鼓励在导出过程中编辑，这项保护保证后续队列、预设及图库接入时也不会混用设置。

## 2. 缓存存储合同与尚未接入部分

`RenderCacheIdentity` 强制提供源内容、解码器、引擎、资源、像素尺寸、输入/输出空间、镜头开关、种子和编辑快照。第一版保守包含所有编辑参数；后续要省略后期字段必须先验证阶段分类。文件名、URI 或缺少资源指纹的版本号不能代替内容身份。

`RenderDiskCache` 保存紧凑的小端 RGBA16 原值；68 字节头包含 magic、版本、宽高、payload 长度、CRC32 和完整键。写入临时文件、同步后更名；读取先验证头、长度及校验和，再开放行读取。

租约拥有独立的文件句柄，未关闭的条目不被替换或淘汰。LRU 在容量内允许新条目，租约阻止释放时拒绝新条目。读写条带最多 32 行，单个 byte 条带最多 1 MiB，写入另有同量级 ShortArray；不创建整帧像素数组。格式的单行上限是 131072 像素；超过它属于不支持缓存的输入，不能以此限制或缩小原图导出。暂存文件短时占用不计入已发布条目容量；生产准入仍需额外预留磁盘空间。

一个目录只允许一个缓存所有者，不能用多个实例绕过租约管理。读取校验覆盖全部文件，会产生 I/O；是否值得使用该缓存必须实测，不能把缓存实现存在等同于导出已经加速。

`Rgba16Source` 统一 native 与磁盘行来源；TIFF writer 使用相同写出逻辑。**当前生产渲染尚未调用此磁盘缓存**，所以没有自动缓存大原片、默认磁盘占用增加或启动后的缓存性能承诺。

尚需完成：可靠的 URI/sourceRevision 与实际打包资源指纹接线、随机种子解析、缓存准入/容量设置、命中收益测量、后台清理和细节 tile 索引。缓存错误的产品回退应重算而不是改变图像质量。

## 3. 基线与核对工具

`tools/record_development_baseline.py --engine <engine目录> --output .tmp/<新目录>` 记录两仓库 HEAD、选定构建输入的 SHA-256、共享工作树 patch 与非忽略的新增源文件副本。只读取显式的源文件、资源和构建脚本范围，不收集签名密钥、账号文件或测试相机原片；输出必须位于 Android 的 `.tmp` 新目录，避免覆盖历史证据。

本轮首次工具测试暴露了 engine 是 Git 子目录时 safe-directory/pathspec 的问题，已修正为查找 owning repository 并使用正确前缀。失败的初始目录不是有效 checkpoint，成功结果在本地 `checkpoint-01` 及后续完整 manifest 中；未改变兄弟引擎工作树。

每次修改后更新 `docs/development-progress.json`，运行 `python tools/check_development_progress.py --write`；不带参数会检查生成文档一致性。校验要求覆盖计划全部 F01—F20、无重复编号、进行中项必须说明剩余内容、已验收项必须有证据且不能仍有遗漏项。

## 4. 验证入口

```powershell
adb shell am instrument -w -e audit render-foundation com.spektralab.android.test/com.spektralab.android.FunctionalAuditInstrumentation
python tools/check_package_boundaries.py
python tools/check_development_progress.py
```

`RenderFoundationAudit` 覆盖不可变/规范值、所有缓存身份字段、跨挂起点串行、取消等待者和异常恢复，及真实单张/批量导出竞争。存储测试包含跨符号位的 16 位样本多行读回、末尾不足条带、租约、容量、取消、重开、坏校验和、非法键及直接/缓存来源 TIFF 一致性。

本次无界面文件变化，圆润主题、顶部工作台和现有布局沿用原实现。曲线/HSL 等新页面仍待开发及渲染验证。最终构建和覆盖回归结果记录在同批进度清单；不将先前性能文档或测试 APK 当作本次最终构建的替代证据。
