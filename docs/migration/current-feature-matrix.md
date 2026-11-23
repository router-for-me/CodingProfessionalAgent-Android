# 当前功能矩阵：65项逐项定位（第五十八批）

本表替代早期迁移矩阵的逐行状态。用户当前范围：只迁移iOS实际实现的功能，UI之后调整。**本表不是完成证明**：“主体在位”只证明当前已找到实现，不能推广为所有边界或实网验收通过；受控/源码fixture/真实Agent证据分开。对本次直接复核出的差异明确标缺口，未做完的深核不默认为通过。

来源：当前工作树RemoteHome/Navigation/NewThread/Search/Controls/SkillComposer/Repository/Ask/Turns/Files/Git与附件实现；iOS HomeView、ThreadModel、DesktopClient、SubAgentViews；历史批次提供测试指针，当前真实运行证据见第五十五至五十七批、第六十三至七十批。各文件名默认位于app/src/main/java/dev/cpa/android，功能UI在feature/remote；Repository为DefaultRemoteDesktopRepository。65项ID集合已与原矩阵程序化比较，一一覆盖无遗漏。

| ID | 当前判定 | owning实现 | 证据与边界 |
|---|---|---|---|
| A01 | 主体在位 | MainActivity、AndroidManifest | 原生单app/API33/语言配置；不是iOS配置复制 |
| A02 | 真实主路径通过 | CpaApplication | ProcessLifecycleOwner；第五十五至五十七批真实前后台恢复 |
| A03 | 真实主路径通过 | RemoteNavigation、RemoteNewThread | 双ID导航/预填/提交后返回；子Agent嵌套为设备测试 |
| A04 | 视觉留待调整 | RemoteScreens、RemoteSubAgentPanel | 不要求复刻iOS标题宽度公式及过渡像素；Android窄屏可用性保留验收 |
| H01 | 主体在位 | RemoteHomeScreen | 空/错/连接状态及管理入口；未逐项实测所有恢复组合 |
| H02 | 真实离开与重开通过 | RemoteHomeProjection | 设备启用筛选、独立置顶、运行/未读排序；第五十一批；第八十四批补阅读自动已读，第八十五批补运行结果未读/有限落盘/重连核对；第八十六批纠正桌面已读字段省略的真实协议断言，离开完成→重开自动已读通过；在途写、跨run及恢复另有主机回归 |
| H03 | 受控验证通过 | HomePreferences、RemoteHomeScreen | 持久折叠、max(5,关注数)、临时全部；第五十二批 |
| H04 | 受控验证通过 | RemoteControls、RemoteForkPanel、RemoteProjectEditor | 首页可直接管理普通/置顶会话且不自动已读；项目操作在项目面板；第六十批加离线原始ID复制，第八十七批补直连网页链接和项目主目录复制；长按位置属UI差异 |
| H05 | 真实主路径通过 | RemoteHomeComposer | 预填/技能/速度命令；第五十六批文本真实闭环；Return行为见C01 |
| H06 | 受控验证通过 | RemoteSearch | 150ms/双端去重/标题优先/30项/回退/禁用；高亮样式另调 |
| H07 | 受控验证通过 | RemoteSearchDialog | 空query新建/优先连接/最近快照；iOS文件快捷动作占位排除 |
| H08 | 真实主路径通过 | RemoteWorkspace、RemoteDirectoryBrowser | 远程home/root/路径/隐藏/代际；测试RemoteWorkspaceTest；第八十二批修复320dp高窗口选择/取消不可达，矮窗口及工作区/语言设备回归通过；第八十三批真实目录输入/隐藏切换/子目录选择通过 |
| H09 | 真实主路径通过 | RemoteProjectController、Repository.updateProject | 路径去重/错误/保存移除；RemoteWorkspaceRepositoryTest及设备面板测试；第八十三批真实改名/路径保存/取消移除/确认移除通过，普通与归档会话及文件保留 |
| H10 | 分叉真实主路径通过 | RemoteForkPanel、RemoteFork | 第六十九批全历史普通分叉复制/独立ID/目标继续/源不变通过，并修复singleTop复用源VM；第七十批消息边界截断真实验证通过；第七十一批worktree首次发送创建/独立目录/源dirty保留/目标clean真实验证通过 |
| S01 | 受控验证通过 | RemoteHomeScreen、ConnectionVault | 启停/排序/删除/全部禁用；ConnectionPreferences设备测试 |
| S02 | 受控验证通过 | ComposerPreferences | 键盘/context/followup/wrap持久化；Live Activity只有iOS偏好，无活动实现，排除 |
| S03 | 真实主路径通过 | RemoteConnectionEditor、AndroidConnectionVault | 独立密码/HTTP确认/真实连接；支持HTTPS为兼容增强，保留TLS校验 |
| S04 | 受控验证通过 | RemoteConnectionEditor、ConnectionConfiguration | Tunnel标识保留/网关凭据修改/加密；设备与仓库测试 |
| S05 | 受控验证通过 | RemoteConnectionEditor、TunnelDiscoveryState | 发现/在线变化/重连/已添加/变更隔离；LiveDiscovery设备测试 |
| S06 | 源码联调通过 | TunnelClientManager、ManagedTunnelConnectionProvider | lease共享/释放/交接/最近网关；真实Go+TS转发契约，业务为fixture |
| N01 | 受控验证通过 | RemoteNewThreadController | 默认项目/模型/推理/速度、分支代际、显式无项目；RemoteNewThreadTest |
| N02 | 真实文本通过 | RemoteNewThreadController、Panel | 第五十六批真实创建发送/返回；上传及图片恢复有受控测试，未全部真实验证 |
| N03 | 主体在位 | RemoteControls | 运行锁模型/速度，推理仍可改；模式控件可由用户重做UI |
| N04 | 功能替代控件 | RemoteControls、RemoteNewThreadPanel | 离散推理按钮替代slider；不强制复刻尺寸/手势，选项可操作性须保持 |
| C01 | 受控验证通过 | RemoteSkillComposer | 首页Return提交预填；会话/新建/编辑默认多行；Return先选命令/技能候选；composition不截获；第七十六批Fcitx真实拼音硬件选词/回车已验证，触屏候选与其他IME未测 |
| C02 | 部分验证 | RemoteSkillComposer、SkillDraft | 原生TextField/selection/composition防旧IME回写；第六十一批真实复制/剪切已测，第六十二批Ctrl+Z/Shift+Z撤销重做已测，第七十六批真实拼音硬件选词已验证，第九十一批真实Fcitx拼音在技能引用后提交“你好”并换行，引用位置与名称保持正确 |
| C03 | 部分验证 | SkillEditor、SkillDraft、RemoteSkillsController | 第六十一批补UTF16整段删除/替换/光标选区边界和触发符复制剪切；仍是文本样式，第六十二批原生撤销恢复引用/重做已验证（硬件快捷键） |
| C04 | 受控验证通过 | SkillDraft、RemotePromptParts | 边界/trim/大小写/保留命令/缺refs旧记录fallback |
| C05 | 受控验证通过 | RemoteSkillComposer | 第八十八批补跨编辑器共享使用次数、entries-updated合并刷新；第六十批候选按焦点隐藏、Return首候选，保留UTF16技能引用和editorEpoch |
| C06 | 视觉留待调整 | RemoteSkillComposer、RemoteHomeComposer | 状态有持续文本；未复刻2s悬浮notice，不影响命令执行；用户后续UI调整 |
| C07 | 受控验证通过 | core/attachments、RemoteAttachments | 25MiB/图像缩放/失败保序；host及Android附件测试 |
| C08 | 部分真实验证 | RemoteAttachmentUi、RemoteDocumentPickerTest | 第六十四批真实DocumentsUI/Photo Picker选择与取消通过，核对文件字节/图片缩略图；第六十五批真实相机确认/取消及缓存清理通过，外部app不可用为受控测试；厂商/永久拒绝矩阵未实测 |
| C09 | 文件真实路径通过 | RemoteAttachments、Repository.send | 第六十六批真实DocumentsUI→上传@path字节校验→模型read返回文件内随机码通过；base64图片仍待模型消费验证；专用模型三次直接图片识别未完整匹配，用户决定沿用配置并保留未验证，未经过Android，不能归因客户端；失败锁定有受控证据 |
| C10 | 受控设备验证通过 | RemoteSentImages、ImagePreview | 解码后台/预览缩放/多图/错误；第七十五批修复矮窗口关闭按钮不可见，真实弹窗切换/缩放/重置/关闭验证；平台控件替代QuickLook，非同缓存算法 |
| R01 | 受控验证通过 | RemoteThreadRoute、RemoteControls | 加载/错误/管理/Git/文件有；会话ID复制已补同H04 |
| R02 | 文本、排队与引导真实主路径通过 | RemoteThreadViewModel、Repository.send | 普通发送/unknown防重放实测；第六十六批文件附件真实读取通过；第七十九批补本地pending，真实Ask中queue→后续执行/清标记通过；第八十批steer同一run内消费及客户端清标记通过；第八十一批停止/重连/人工确认受控回归通过并修复idle确认残留；图片及真实故障联调仍需核查 |
| R03 | 真实主路径通过 | DefaultRemoteDesktopRepository | 快照/live代际/重连/完整替换；真实停止重连和丢回执，竞态有host测试 |
| R04 | 真实主路径通过 | RemoteResume、RemoteControls、Repository.runAction | 第五十九批补完成/中断/重启/工具结果/停止fallback；仓库再次校验并拒绝子会话；第六十三批真实停止→恢复→完成→生命周期重连无重放通过 |
| R05 | 真实文本主路径通过 | RemoteMessageEditor、RemoteEditPanel | 编辑条件/保留图片/取消/禁重复；采用弹层替代inline；第六十七批真实文本编辑重跑通过 |
| R06 | 真实文本主路径通过 | RemoteMessageEditor、Repository.editMessage | 匹配start前等待/unknown/权威快照/late-start；第六十七批真实修改保留用户ID/替换旧回复/完成/重连无重放通过 |
| R07 | 主体在位 | RemoteControls、RemoteThreadViewModel | 模型/速度运行锁、推理开放、命令条件/串行管理；弹层关闭提交时机为平台UI差异 |
| R08 | 受控验证通过 | RemoteTimeline | 第七十三批补已完成顶部/运行跟底/新用户恢复跟随/编辑暂停与成功后跟随/短内容增长；恢复快照不误跟随，真实IME开关仍保持底部；第七十六批Fcitx硬件拼音选词/回车通过 |
| R09 | 受控验证通过 | RemoteTurns | 阶段/compaction/紧随结果/paused/最终文本；RemoteTurnsTest/TurnUiTest |
| R10 | 受控验证通过 | RemoteActivity、RemoteTurnActivity、RemoteToolSummary | thinking隐藏/nested去重/截断/摘要/展开；真实Ask工具运行通过但非所有工具实测 |
| R11 | 视觉留待调整 | RemoteTurnActivityUi | 不要求复制shimmer参数；运行状态功能需保留，不声称全局reduced-motion已验收 |
| R12 | 主体在位 | RemotePromptActions、RemoteTurnUi | copy/share/edit/fork/time；菜单摆放差异不阻迁移，真实分享目标未全测 |
| R13 | 受控验证通过 | feature/remote/markdown | CommonMark/未闭合代码/table/链接/wrap；静态解析与设备渲染测试 |
| R14 | 真实custom通过 | RemoteAsk、RemoteThreadRoute | 第五十七批自动呈现/停倒计时/custom/Agent继续；选项/skip/unknown边界受控测试 |
| R15 | 受控验证通过 | RemoteCapabilities.remoteContextUsage | 窗口覆盖/最后usage/100%上限/偏好；ContextUsageTest和设备测试 |
| R16 | 受控验证通过 | RemoteSubAgentPills、RemoteActivity | record/placeholder/nested状态及去重；第七十七批真实spawn→completed记录/只读回复展示通过；多层及异常仍受控 |
| R17 | 真实只读主路径通过 | RemoteSubAgentPanel | 第七十七批真实完成子会话打开/回复/返回且快照不变；嵌套/状态/时长有受控证据；第七十二批已完成打开顶部/运行中跟底及状态变化不抢位置设备验证；iOS fork方法实际拒绝子会话，排除该虚假完成路径 |
| G01 | 受控验证通过 | RemoteGitPanel、RemoteFilesPanel | 工作路径/override/切换重置/错误；分两个面板为UI布局差异 |
| G02 | 受控验证通过 | data/repository/git | quoted八进制/CRLF/hunk/binary/rename/copy；GitDiffParserTest |
| G03 | 真实主路径通过 | data/repository/git、Repository.gitReview | HEAD/porcelain/no-index/unborn；第六十八批真实工作区修改/rename/中文untracked验证；unborn等边界仍为受控 |
| G04 | 真实主路径通过 | RemoteGit、Repository.gitReview | origin/base...target且无local兜底；加载代际与分支控件；第六十八批真实origin/main...feature与UI切换通过 |
| G05 | 受控验证通过 | RemoteGitPanel | 首文件≤400自动展开/全部展开/行号；第九十三批补代码语法标注和展开后逐行懒加载，槽宽与配色细节留待UI调整 |
| G06 | 真实主路径通过 | RemoteGitPanel、RemoteFilesController | repoRoot相对路径/打开只读预览；第六十八批真实文件内容与复制原始路径通过 |
| G07 | 真实主路径通过 | RemoteFiles | 懒加载/排除目录/仅已加载树筛选/错误重试；第六十八批真实嵌套展开/已加载筛选/打开预览通过，错误重试仍受控 |
| G08 | 真实预览及词法回归通过 | RemoteFilesPreview | 200000字符/行号/复制路径/只读/换行；第九十三批文件与Git差异共用语法标注，保留原文；普通、Dockerfile/Makefile、长行和浅深色有回归 |
| Q01 | 视觉留待调整 | core/designsystem | 已有浅深主题；不签署P3/逐色/像素等价 |
| Q02 | 部分验证 | RemoteSkillComposer、RemoteThreadScreen | 输入/IME/附件下发送可见设备用例；第七十六批Fcitx硬件拼音已测，第九十一批技能混合拼音通过；交付版 2.0 字号下目录选择、首页会话管理和输入法开关三项通过。触屏候选及其他厂商 IME 未逐一验收 |
| Q03 | 视觉留待调整 | RemoteNavigation | 导航行为有真实证据；不要求iOS逐帧标题identity/opacity复刻 |
| Q04 | 源码联调通过 | core/network、core/storage | RPC/凭据/共享隧道/租约/大包/重连；真实源码fixture及定向设备记录 |
| Q05 | 分层证据存在 | app/src/test、app/src/androidTest | 交付版362主机；完整默认设备、六项条件定向见本次交付记录；另核心/丢回执/新建/Ask/Resume/文件/编辑/Git/普通fork/边界fork/worktree真实定向记录，不能相加成全套全过 |
| Q06 | 视觉留待调整 | feature/remote | 用户明确后续调整UI；不把素材/系统材质/动画像素列成功能完成阻塞 |

## 确认缺口与执行顺序

1. **R04 Resume资格判断已补**（第五十九批）：纯函数、仓库再次校验、顶层会话限制、UI门控及停止标记已验证；第六十三批真实主路径已验收，2用户/3run/1恢复点击。
2. **C01/C05键盘与候选已补**（第六十批）：首页Return提交、多行换行、候选优先及失焦隐藏；composition保护有单测，第七十六批已用Fcitx5验证真实中文候选提交与回车。iOS会话与新建实际均使用ExpandedComposer，不新增模式开关。
3. **H04/R01复制会话ID已补**（第六十批）：明确复制按钮，离线复制原始ID有设备测试。
4. C02/C03真实Fcitx技能混合输入已过；C08厂商/权限差异仍为平台覆盖边界。子Agent实网见第七十七批。图片及保图编辑的模型端验证按用户决定保留。

## 非完成阻塞与能力边界

- 视觉参数、动画、iOS系统组件样式由用户后续调整；不以“无UI要求”为由省略键盘、复制或会话状态功能。
- 子Agent fork在iOS实际方法拒绝，首页附件和搜索文件快捷动作是空动作/占位，Live Activity只有偏好，均不新增为迁移目标。
- **任务通知**：Android 已接入可选 FCM，包括桌面注册、任务完成/Ask 提示和按桌面打开会话。旧 65 项验收未包括推送；真实 Firebase 投递需要另行联调。[配置和验证](../task-notifications.md)。
- 原F01…F17用例是跨行验收索引，不能从本表“主体在位”推断整组通过；F06/F07已有受控键盘验证，第七十六批Fcitx真实拼音硬件选词/回车已验证，触屏候选与其他IME未测，F10 Resume规则与第六十三批实网主路径已验证；F17像素/动画部分因用户最新指令非本轮交付门槛，原生可用性仍保留。
- 直连/tunnel协议细项继续由原环境与契约文档、网络/存储测试覆盖；真实Go+TS转发不等于所有Agent业务；现有真实模型闭环只覆盖已记录的路径。

第六十批已补键盘候选与ID复制；验证范围见 Git 历史中的 `composer-return` 记录。目标active，剩余真实高级业务与平台通知边界仍需核查，不以测试总数替代整体完成证据。
