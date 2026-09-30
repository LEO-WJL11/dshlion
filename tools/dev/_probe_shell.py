import subprocess, time, sys, threading

def probe(shell):
    print('==== shell =', shell)
    p = subprocess.Popen([shell, '-NoLogo', '-NoProfile', '-NonInteractive', '-Command', '-'],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = []
    def reader():
        for line in p.stdout:
            out.append(line.decode('utf-8', 'replace').rstrip('\r\n'))
    t = threading.Thread(target=reader, daemon=True)
    t.start()

    def send(cmd, wait=1.2):
        out.clear()
        p.stdin.write((cmd + '\n').encode('utf-8'))
        p.stdin.flush()
        time.sleep(wait)
        return list(out)

    print('1)', send('[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; Write-Output ("MARK ok=$? code=$LASTEXITCODE")'))
    print('2)', send('cd C:\\Windows; Write-Output ("MARK ok=$? code=$LASTEXITCODE")'))
    print('3)', send('$x = 41; $x = $x + 1; Write-Output ("MARK ok=$? code=$LASTEXITCODE")'))
    print('4)', send('Write-Output "x=$x  dir=$((Get-Location).Path)"; Write-Output ("MARK ok=$? code=$LASTEXITCODE")'))
    print('5)', send('echo 中文测试; Write-Output ("MARK ok=$? code=$LASTEXITCODE")'))
    print('6)', send('cmd /c "dir /b /ad C:\\ | findstr /i windows"; Write-Output ("MARK ok=$? code=$LASTEXITCODE")'))
    print('7)', send('nosuchcommand123; Write-Output ("MARK ok=$? code=$LASTEXITCODE")'))
    print('8)', send('Write-Output ("MARK ok=$? code=$LASTEXITCODE")'))
    print('9) multiline:', send('if ($true) {\n  Write-Output "inside"\n}\nWrite-Output ("MARK ok=$? code=$LASTEXITCODE")', wait=1.5))
    print('alive:', p.poll() is None)
    p.kill()

probe('powershell')
