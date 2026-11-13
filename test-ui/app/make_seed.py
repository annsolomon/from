#!/usr/bin/env python3
"""Regenerates src/seed.js (the lite mock data) from the generated trail fixtures in test-ui/fixtures/seed."""
import json, os
here = os.path.dirname(os.path.abspath(__file__)); fx = os.path.join(here, '..', 'fixtures', 'seed')
L = lambda f: (lambda d: d['data'] if isinstance(d, dict) and 'data' in d else d)(json.load(open(os.path.join(fx, f + '.json'), encoding='utf-8')))
cards, cats, mer, exp, uc, per = L('catalog-cards'), L('categories'), L('merchants'), L('expenses'), L('user-cards'), L('personas')
held = {c['cardProductId'] for c in uc}
keep = ([c for c in cards if c['id'] in held] + [c for c in cards if c['id'] not in held])[:18]
asha = next(p['accountId'] for p in per if p['key'] == 'asha')
ae = sorted([e for e in exp if e['accountId'] == asha], key=lambda e: e['occurredAt'], reverse=True)[:140]
re_ = sorted([e for e in exp if e['accountId'] != asha], key=lambda e: e['occurredAt'], reverse=True)[:40]
want = {'croma', 'swiggy', 'makemytrip-flights', 'bigbasket', 'indian-oil', 'myntra', 'bookmyshow', 'oyo', 'uber', 'zomato', 'amazon', 'flipkart', 'irctc', 'ola',
        'zepto', 'blinkit', 'starbucks', 'netflix', 'apollo-pharmacy', 'indigo', 'cleartrip', 'taj-hotels', 'itc-hotels'}
used = {e['merchantId'] for e in ae + re_}
mk = [m for m in mer if m['id'] in used or m['slug'] in want]
mk += [m for m in mer if m not in mk][:max(0, 110 - len(mk))]
slim = lambda e: {k: e[k] for k in ['id', 'accountId', 'occurredAt', 'amountPaise', 'merchantDisplay', 'merchantId', 'categoryPath', 'categorySource', 'resolutionConfidence', 'paymentMethod', 'userCardId', 'note', 'tags', 'source', 'version']}
data = {'cards': keep, 'categories': cats, 'merchants': [{k: m[k] for k in ['id', 'slug', 'name', 'defaultCategory', 'aliases']} for m in mk],
        'expenses': [slim(e) for e in ae + re_], 'userCards': uc, 'personas': per}
open(os.path.join(here, 'src', 'seed.js'), 'w', encoding='utf-8').write('const SEED=' + json.dumps(data, separators=(',', ':')) + ';\n')
print('seed.js written:', len(keep), 'cards,', len(ae) + len(re_), 'expenses,', len(mk), 'merchants')
