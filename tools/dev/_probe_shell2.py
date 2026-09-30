import subprocess, time, threading, base64

def enc(cmd):
    return "$__c=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + \
           base64.b64encode(cmd.encode('utf-8')).decode('ascii') + "')); Invoke-Expression $__c"

def probe(shell):
    print('==== shell =', shell)
    p = subprocess.Popen([shell, '-NoLogo', '-NoProfile', '-NonInteractive', '-Command', '-'],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    raw = []
    def reader():
        for line in p.stdout:
            raw.append(line)
    t = threading.Thread(target=reader, daemon=True)
    t.start()

    def send(cmd, wait=1.2, dec='utf-8'):
        raw.clear()
        data = (enc(cmd) + '\nWrite-Output ("__M__ ok=$? code=$LASTEXITCODE")\n').encode('ascii')
        p.stdin.write(data)
        p.stdin.flush()
        time.sleep(wait)
        return [b.decode(dec, 'replace').rstrip('\r\n') for b in raw]

    print('0 utf8 out:', send('[Console]::OutputEncoding=[Text.Encoding]::UTF8; $OutputEncoding=[Text.Encoding]::UTF8; "set"'))
    print('1 chinese :', send('Write-Output "中文测试 abc"'))
    print('1b chinese file:', send('Set-Content -Path C:\\Users\\Leo\\Desktop\\lion-code\\_probe_中文.txt -Value "中文内容" -Encoding UTF8; Get-Content C:\\Users\\Leo\\Desktop\\lion-code\\_probe_中文.txt'))
    print('2 cd      :', send('cd C:\\Windows'))
    print('3 state   :', send('$x = 7'))
    print('4 read    :', send('Write-Output "x=$x loc=$((Get-Location).Path)"'))
    print('5 multiline:', send('if ($true) {\n  Write-Output "inside-if"\n}\nforeach ($i in 1..3) { Write-Output "i=$i" }', wait=2.0))
    print('6 function:', send('function hi($n) { return "hi $n" }\nhi "lion"', wait=1.5))
    print('7 fail    :', send('nosuchcommand123', wait=1.5))
    print('8 native fail:', send('cmd /c "exit 3"', wait=1.5))
    print('9 native ok  :', send('cmd /c "ver"', wait=1.5))
    print('10 dir listing:', send('ls C:\\Windows\\ | Select-Object -First 2 | Out-String', wait=2.0))
    print('alive:', p.poll() is None)
    p.kill()

probe('powershell')
