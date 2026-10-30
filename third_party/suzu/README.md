# Suzu inference layer

`model/` is copied without modification from https://github.com/1zumiii/suzu-ime,
commit `a25db7850ed177bf05f5f79eee9a206e5586b955`, `core/model/`.
Copyright its contributors; GPL-3.0, see LICENSE and UPSTREAM-NOTICE.md.
Only the model layer is used. No Rime daemon, Lua, lexicon or candidate generation is included.

llama.cpp is pinned as a separate MIT-licensed submodule at b11632.
Model weights are Suzu-2.4 by IzumiThe3rd, CC BY-SA 4.0, fetched by
`scripts/fetch-suzu.ps1`; see the bundled `assets/suzu/NOTICE.md`.
