# 飞念语词库

两类资源分别使用：

- 拼音选词：计算机、历史人名、地名、成语；由 THUOCL 词表生成标准拼音，删除空行、损坏词频、拉丁混合项及过长项，少量现代计算机术语由本项目补充。人名含多音字，当前人工校对了部分常见项，不能视为全部读音已经校对。
- 原文接续：精选古诗词和八字以上固定成语，按输入末尾直接检索下一处句读前的原文。IT 和人名不作为接续规则。不推断缺字，不做模糊纠错，保留作品异文。

诗词来源：https://github.com/chinese-poetry/chinese-poetry
revision b8594f81a89752241442f2ce267d6f66f96704ee
MIT，Copyright (c) 2016 JackeyGao。原许可证见 LICENSE-chinese-poetry.txt。

领域词表及高频诗句筛选来源：https://github.com/thunlp/THUOCL
revision a30ce79d895d01ab5132a5c74c29703ff7efb4cc
MIT，Copyright (c) 2018 THUNLP。原许可证见 LICENSE-THUOCL.txt。
使用了清华大学开放中文词库：韩世依、张钰晖、马云山、涂存超、郭志芃、刘知远、孙茂松，THUOCL：清华大学开放中文词库，2016。

构建脚本 scripts/build-dictionaries.py：保留唐诗三百首、宋词三百首和千家诗，其他作品按 THUOCL 诗句 DF>=20 筛选，长成语保留 8–24 汉字。生成拼音时使用 pypinyin 0.55.0（MIT），构建时简繁处理使用 opencc-python-reimplemented 0.1.7（Apache-2.0）；手机运行时复用现有 OpenCC。包内数据文件、条目数量和 SHA-256 见 manifest.json。
