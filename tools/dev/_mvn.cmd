@echo off
rem 串行化 Maven 构建的批处理入口（PowerShell 执行策略禁止跑未签名 .ps1，所以用 .cmd 包一层）
rem 用法： tools\dev\_mvn.cmd -o -q -DskipTests compile
python "%~dp0_mvn.py" %*
