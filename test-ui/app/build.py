#!/usr/bin/env python3
"""Builds test-ui/app/index.html (one self-contained page) from src/. Run after editing anything in src/."""
import os, re
here = os.path.dirname(os.path.abspath(__file__)); src = os.path.join(here, 'src')
order = ['seed.js', 'calc.js', 'emi.js', 'core.js', 'api.js', 'live.js', 'db.js', 'engines.js', 'handlers.js', 'handlers2.js', 'runall.js',
         'ui-shell.js', 'ui-user.js', 'ui-money.js', 'ui-emi.js', 'ui-market.js', 'ui-account.js', 'ui-admin.js', 'ui-platform.js', 'main.js']
js = '\n'.join(re.sub(r"if \(typeof module !== 'undefined'\) module.exports = \w+;", '', open(os.path.join(src, f), encoding='utf-8').read()) for f in order)
js = js.replace("typeof DexCalc !== 'undefined' ? DexCalc : require('./calc.js')", 'DexCalc')
css = open(os.path.join(src, 'style.css'), encoding='utf-8').read()
html = f'''<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Dexter v2 — full frontend (mock + live)</title>
<style>{css}</style></head><body>
<script>{js}</script></body></html>'''
open(os.path.join(here, 'index.html'), 'w', encoding='utf-8').write(html)
print('index.html', len(html), 'bytes')
