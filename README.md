# LSPosed Framework

## 介绍 
一个Zygisk模块，用于提供一个ART钩子框架，使用LSPlant来提供与Xposed的API。

> Xposed 是一个框架，可以改变系统和应用程序的行为，而无需触碰任何软件的包体。这听起来确实很棒，对吧？因为这意味着模块可以在不同的版本甚至 ROM 上正常运行，而无需任何更改（只要开发者不要写的太死板）。它也很容易关掉。由于所有更改都是在内存中完成的，您只需停用模块并重启即可恢复原来的样子。还有许多其他优点，但这里再提一个：多个模块可以对系统或应用程序的同一部分进行更改，您必须选择一个。除非开发者们构建多个具有不同组合的模块，否则没办法把它们结合在一起。

## 支持的 Android 版本

Android 8.1 ~ 16

## 安装方法

1. 安装 Magisk 或者 KernelSU （包括它们的分支），但要确保 MAGISK_VER_CODE 高于 26000
2. 安装 [ZygiskNext](https://github.com/Dr-TSNG/ZygiskNext/releases) 或者 [ReZygisk](https://github.com/PerformanC/ReZygisk)
> 不推荐使用Magisk自带的Zygisk功能
3. [Download](#下载)然后安装LSPosed模块
4. 重启
5. 尝试打开LSPosed管理器吧，无论使用Actions按钮还是通知，又或是拨打*#*#5776733*#*#，这些都是好办法
6. 玩的开心

## 下载

- 如果你追求稳定，请前往[Github Releases page](https://github.com/re-zero001/LSPosed/releases)
- 如果你想要最新的功能，看看这里[Github Actions](https://github.com/re-zero001/LSPosed/actions/workflows/core.yml?query=branch%3Adev)
> 注意Actions的下载需要登录GitHub

## 获取帮助

**我们只收集**最新的构建**的问题**
- GitHub issues: [Issues](https://github.com/PKQISPKQ/LSPosed-Toop/issues/)

## 开发者们

欢迎编写基于 LSPosed 框架的 Xposed 模块。基于 LSPosed 框架的模块与原始 Xposed 框架完全兼容，反之，基于 Xposed 框架的模块也能够很好地与 LSPosed 框架配合使用。

- [Xposed Framework API](https://api.xposed.info/)

原版LSPosed开发者拥有他们自己的模块库。我们欢迎开发者向他们的库提交模块，然后可以在LSPosed中下载这些模块。

- [LSPosed Module Repository](https://github.com/Xposed-Modules-Repo)

## Credits 

- [Magisk](https://github.com/topjohnwu/Magisk/): makes all these possible
- [ZygiskNext](https://github.com/Dr-TSNG/ZygiskNext): provides a way to inject code into zygote process
- [XposedBridge](https://github.com/rovo89/XposedBridge): the OG Xposed framework APIs
- [Dobby](https://github.com/re-zero001/Dobby): used for inline hooking
- [LSPlant](https://github.com/LSPosed/LSPlant): the core ART hooking framework
- [LSPosed](https://github.com/LSPosed/LSPosed): fork source
- [EdXposed](https://github.com/ElderDrivers/EdXposed): LSPosed fork source
- [xz_embedded](https://github.com/tukaani-project/xz-embedded):decompress debug_info section into stripped libraries
- [system_properties](https://github.com/topjohnwu/system_properties):switch properties access within LSPosed
- ~[SandHook](https://github.com/ganyao114/SandHook/): ART hooking framework for SandHook variant~
- ~[YAHFA](https://github.com/rk700/YAHFA): previous ART hooking framework~
- ~[dexmaker](https://github.com/linkedin/dexmaker) and [dalvikdx](https://github.com/JakeWharton/dalvik-dx): to dynamically generate YAHFA hooker classes~
- ~[DexBuilder](https://github.com/LSPosed/DexBuilder): to dynamically generate YAHFA hooker classes~

## License

LSPosed使用了**GNU General Public License v3 (GPL-3)** (http://www.gnu.org/copyleft/gpl.html)协议。
