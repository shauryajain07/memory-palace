# Third-Party Notices

Memory Palace is licensed under the MIT License (see `LICENSE`). The mod JAR bundles the following libraries as unmodified nested JARs in `META-INF/jars/`. Each library remains under its own license.

| Library | Version | License | Source |
|---|---|---|---|
| commonmark-java and its GFM tables, GFM strikethrough, and task list items extensions | 0.31.0 | BSD-2-Clause | https://github.com/commonmark/commonmark-java |
| JLaTeXMath | 1.0.7 | GPL-2.0 with Classpath Exception | https://github.com/opencollab/jlatexmath |
| Password4j | 1.8.4 | Apache-2.0 | https://github.com/Password4j/password4j |
| fabric-permissions-api | 0.7.0 | MIT | https://github.com/lucko/fabric-permissions-api |
| java-diff-utils | 4.15 | Apache-2.0 | https://github.com/java-diff-utils/java-diff-utils |
| NightConfig (core, toml) | 3.8.2 | LGPL-3.0 | https://github.com/TheElectronWill/night-config |
| Apache PDFBox (pdfbox, pdfbox-io, fontbox) | 3.0.8 | Apache-2.0 | https://pdfbox.apache.org/ |
| Apache Commons Logging | 1.4.0 | Apache-2.0 | https://commons.apache.org/proper/commons-logging/ |

The book text uses Computer Modern, the LaTeX typeface by Donald E. Knuth. The font files in `assets/memorypalace/font/` (`jlm_cmr10.ttf`, `jlm_cmbx10.ttf`, `jlm_cmti10.ttf`, `jlm_cmbxti10.ttf`, `jlm_cmtt10.ttf`, and the sans-serif `jlm_cmss10.ttf`, `jlm_cmssbx10.ttf`, `jlm_cmssi10.ttf` used by the built-in Clean white resource pack) are copied unchanged from JLaTeXMath 1.0.7, which ships them under Knuth's license: they may be copied and distributed, and modified versions must carry a different name. The license text is in `assets/memorypalace/font/license_computer_modern.txt`.

The offline tool JAR (`memory-palace-decrypt`) contains the unpacked classes of the following libraries:

| Library | License | Source |
|---|---|---|
| Password4j | Apache-2.0 | https://github.com/Password4j/password4j |
| SLF4J API | MIT | https://github.com/qos-ch/slf4j |

The full license text of each library is available in its source repository. Each nested JAR can be replaced with a compatible build of the same library.
