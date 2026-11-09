#!/usr/bin/env python3
"""Publish metadata for one exact, signature-verified APK; no secrets in the manifest."""
import argparse,hashlib,json,os,pathlib,re,subprocess

p=argparse.ArgumentParser();p.add_argument('--apk',required=True);p.add_argument('--output',required=True);args=p.parse_args()
apk=pathlib.Path(args.apk);build=pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
subprocess.run([str(build/'apksigner'),'verify',str(apk)],check=True)
badging=subprocess.check_output([str(build/'aapt'),'dump','badging',str(apk)],text=True)
certs=subprocess.check_output([str(build/'apksigner'),'verify','--print-certs',str(apk)],text=True)
package=re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",badging,re.M)
minimum=re.search(r"^sdkVersion:'(\d+)'",badging,re.M)
certificate=re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})',certs)
assert package and minimum and len(certificate)==1,'Missing APK metadata or unsupported signer count'
assert package[1]=='za.co.cttrains.app' and 'application-debuggable' not in badging,'Expected non-debuggable CT Trains APK'
with apk.open('rb') as f:digest=hashlib.file_digest(f,'sha256').hexdigest()
manifest={'schema':1,'packageName':package[1],'versionCode':int(package[2]),'versionName':package[3],'minSdk':int(minimum[1]),'apk':apk.name,'sizeBytes':apk.stat().st_size,'sha256':digest,'certificateSha256':certificate[0].lower()}
pathlib.Path(args.output).write_text(json.dumps(manifest,indent=2)+'\n')
print(f'Update manifest verified for {apk.name}')
