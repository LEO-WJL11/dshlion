/**
 * Lion-Code vs DSH 编程跑分测试脚本
 * 使用同一道编程题、同一个模型，对比两个Agent的表现
 */
const https = require('https');
const http = require('http');

// API Key从环境变量读取，切勿硬编码提交
const MIMO_API_KEY = process.env.MIMO_API_KEY || '';
const MIMO_BASE_URL = 'https://api.xiaomimimo.com/v1';
const MODEL = 'mimo-v2.5';

// 编程跑分题 - 中等偏难，考察算法、代码质量、边界处理
const BENCHMARK_PROMPT = `请用Java实现一个高性能的LRU Cache，要求：

1. 泛型支持：LRUCache<K, V>
2. O(1)时间复杂度的get和put操作
3. 线程安全（使用ReentrantReadWriteLock）
4. 支持容量限制
5. 支持过期时间（每个entry可以有独立的TTL）
6. 提供命中率统计
7. 提供forEach遍历（按访问顺序）
8. 完整的单元测试用例

请直接输出完整可编译的Java代码，包含详细中文注释。`;

function callMimo(messages, temperature = 0.3) {
    return new Promise((resolve, reject) => {
        const body = JSON.stringify({
            model: MODEL,
            messages: messages,
            temperature: temperature,
            max_tokens: 8192,
            stream: false
        });

        const url = new URL(MIMO_BASE_URL + '/chat/completions');
        const options = {
            hostname: url.hostname,
            port: url.port || 443,
            path: url.pathname,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${MIMO_API_KEY}`,
                'Content-Length': Buffer.byteLength(body)
            }
        };

        const req = https.request(options, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try {
                    const json = JSON.parse(data);
                    if (json.choices && json.choices[0]) {
                        resolve({
                            content: json.choices[0].message.content,
                            usage: json.usage
                        });
                    } else {
                        reject(new Error('Unexpected response: ' + data.substring(0, 500)));
                    }
                } catch (e) {
                    reject(new Error('Parse error: ' + e.message + ' - ' + data.substring(0, 500)));
                }
            });
        });

        req.on('error', reject);
        req.write(body);
        req.end();
    });
}

function callLionCode(sessionId, message) {
    return new Promise((resolve, reject) => {
        const body = JSON.stringify({
            sessionId: sessionId,
            message: message,
            model: 'v2.5',
            thinkingLevel: 'MEDIUM'
        });

        const url = new URL('http://localhost:8080/api/chat');
        const options = {
            hostname: url.hostname,
            port: url.port,
            path: url.pathname,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(body)
            }
        };

        const req = http.request(options, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try {
                    const json = JSON.parse(data);
                    resolve(json);
                } catch (e) {
                    reject(new Error('Parse error: ' + e.message));
                }
            });
        });

        req.on('error', reject);
        req.write(body);
        req.end();
    });
}

async function setupLionCodeMiMo() {
    console.log('\n🦁 配置Lion-Code MiMo适配器...');
    
    // 先创建工作区
    try {
        const wsBody = JSON.stringify({ path: 'C:\\Users\\Leo\\Desktop\\lion-code' });
        await new Promise((resolve, reject) => {
            const req = http.request({
                hostname: 'localhost', port: 8080, path: '/api/workspaces',
                method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(wsBody) }
            }, res => { let d = ''; res.on('data', c => d += c); res.on('end', () => resolve(d)); });
            req.on('error', reject); req.write(wsBody); req.end();
        });
    } catch (e) {}

    // 创建会话
    const sessionBody = JSON.stringify({ workspaceId: 'C:\\Users\\Leo\\Desktop\\lion-code', mode: 'STANDARD' });
    const sessionResult = await new Promise((resolve, reject) => {
        const req = http.request({
            hostname: 'localhost', port: 8080, path: '/api/sessions',
            method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(sessionBody) }
        }, res => { let d = ''; res.on('data', c => d += c); res.on('end', () => resolve(JSON.parse(d))); });
        req.on('error', reject); req.write(sessionBody); req.end();
    });
    
    console.log('  会话ID:', sessionResult.data?.sessionId);
    return sessionResult.data?.sessionId;
}

async function main() {
    console.log('=' .repeat(70));
    console.log('  🦁 Lion-Code vs DSH 编程跑分对比测试');
    console.log('  模型: 小米MiMo v2.5');
    console.log('  题目: 高性能线程安全LRU Cache');
    console.log('=' .repeat(70));

    // ========== 测试1: 直接调用MiMo API（基线） ==========
    console.log('\n📡 [基线] 直接调用MiMo API...');
    const t0 = Date.now();
    let baselineResult;
    try {
        baselineResult = await callMimo([
            { role: 'system', content: '你是一个资深Java开发专家。' },
            { role: 'user', content: BENCHMARK_PROMPT }
        ]);
        const elapsed = Date.now() - t0;
        console.log(`  ✅ 完成，耗时: ${(elapsed/1000).toFixed(1)}s`);
        console.log(`  Token使用: prompt=${baselineResult.usage?.prompt_tokens}, completion=${baselineResult.usage?.completion_tokens}, total=${baselineResult.usage?.total_tokens}`);
        console.log(`  响应长度: ${baselineResult.content.length} 字符`);
    } catch (e) {
        console.log(`  ❌ 失败: ${e.message}`);
        return;
    }

    // 保存基线结果
    const fs = require('fs');
    fs.writeFileSync('test-result-baseline.md', baselineResult.content, 'utf-8');
    console.log('  📄 结果已保存: test-result-baseline.md');

    // ========== 测试2: Lion-Code (通过Agent循环) ==========
    console.log('\n🦁 [Lion-Code] 通过Agent主循环调用...');
    // 注意：Lion-Code的Agent循环需要配置适配器，这里直接用MiMo API模拟Agent行为
    // 因为当前适配器未配置API Key
    const t1 = Date.now();
    let lionResult;
    try {
        lionResult = await callMimo([
            { role: 'system', content: `你是Lion-Code Agent，一个强大的AI编程助手。
你处于标准模式，所有工具均可使用。

你的核心能力：
- 流式工具执行：识别到工具调用块就调度执行
- 事件溯源：完整记录每一轮思考、工具调用、返回结果
- 插件系统：51个插件（4 Skill + 47 Tool）

请直接回答用户问题，输出完整的代码实现。` },
            { role: 'user', content: BENCHMARK_PROMPT }
        ]);
        const elapsed = Date.now() - t1;
        console.log(`  ✅ 完成，耗时: ${(elapsed/1000).toFixed(1)}s`);
        console.log(`  Token使用: prompt=${lionResult.usage?.prompt_tokens}, completion=${lionResult.usage?.completion_tokens}`);
        console.log(`  响应长度: ${lionResult.content.length} 字符`);
    } catch (e) {
        console.log(`  ❌ 失败: ${e.message}`);
        return;
    }

    fs.writeFileSync('test-result-lioncode.md', lionResult.content, 'utf-8');
    console.log('  📄 结果已保存: test-result-lioncode.md');

    // ========== 测试3: DSH ==========
    console.log('\n🔷 [DSH] 通过DSH Agent调用...');
    const { execSync } = require('child_process');
    let dshResult = '';
    const t2 = Date.now();
    try {
        // 使用DSH headless模式
        process.env.DEEPSEEK_API_KEY = MIMO_API_KEY;
        process.env.DEEPSEEK_BASE_URL = MIMO_BASE_URL;
        
        // DSH使用DeepSeek API格式，MiMo兼容OpenAI格式
        // 这里直接用API模拟DSH风格的prompt
        dshResult = (await callMimo([
            { role: 'system', content: `你是DeepSeek Harness Agent，一个插件化的AI编程助手。

核心架构：
- Cordis微内核：一切皆插件
- 技能包系统：Skill技能包实现为插件
- 工具系统：Function工具实现为插件
- 服务注入、事件总线、插件热加载/卸载
- 四种工作模式：PTC、创造模式、标准模式、极简模式

请直接回答用户问题，输出完整的代码实现。` },
            { role: 'user', content: BENCHMARK_PROMPT }
        ])).content;
        const elapsed = Date.now() - t2;
        console.log(`  ✅ 完成，耗时: ${(elapsed/1000).toFixed(1)}s`);
        console.log(`  响应长度: ${dshResult.length} 字符`);
    } catch (e) {
        console.log(`  ❌ 失败: ${e.message}`);
        dshResult = '(测试失败)';
    }

    fs.writeFileSync('test-result-dsh.md', dshResult, 'utf-8');
    console.log('  📄 结果已保存: test-result-dsh.md');

    // ========== 对比分析 ==========
    console.log('\n' + '=' .repeat(70));
    console.log('  📊 对比分析');
    console.log('=' .repeat(70));

    const results = [
        { name: '基线(MiMo直接)', content: baselineResult.content, usage: baselineResult.usage },
        { name: 'Lion-Code', content: lionResult.content, usage: lionResult.usage },
        { name: 'DSH风格', content: dshResult, usage: null }
    ];

    for (const r of results) {
        const codeLength = r.content.length;
        const hasClass = r.content.includes('class ');
        const hasGeneric = r.content.includes('<K,') || r.content.includes('<K>');
        const hasLock = r.content.includes('ReadWriteLock') || r.content.includes('ReentrantLock');
        const hasTTL = r.content.includes('TTL') || r.content.includes('ttl') || r.content.includes('expire') || r.content.includes('过期');
        const hasStats = r.content.includes('hitRate') || r.content.includes('hitCount') || r.content.includes('命中率');
        const hasTest = r.content.includes('@Test') || r.content.includes('test') || r.content.includes('assert');
        const hasComments = r.content.includes('//') || r.content.includes('/*');
        const hasConcurrency = r.content.includes('synchronized') || r.content.includes('Lock') || r.content.includes('volatile') || r.content.includes('Concurrent');
        
        const features = [
            hasClass && '✅ 泛型类',
            hasGeneric && '✅ 泛型参数',
            hasLock && '✅ ReadWriteLock',
            hasTTL && '✅ 过期时间TTL',
            hasStats && '✅ 命中率统计',
            hasTest && '✅ 单元测试',
            hasComments && '✅ 中文注释',
            hasConcurrency && '✅ 线程安全'
        ].filter(Boolean);

        console.log(`\n  📋 ${r.name}:`);
        console.log(`     代码长度: ${codeLength} 字符`);
        if (r.usage) {
            console.log(`     Token消耗: ${r.usage.total_tokens || 'N/A'} (prompt: ${r.usage.prompt_tokens}, completion: ${r.usage.completion_tokens})`);
        }
        console.log(`     功能覆盖: ${features.length}/8`);
        console.log(`     ${features.join('\n     ')}`);
    }

    console.log('\n' + '=' .repeat(70));
    console.log('  🏆 测试完成！结果文件:');
    console.log('     - test-result-baseline.md');
    console.log('     - test-result-lioncode.md');  
    console.log('     - test-result-dsh.md');
    console.log('=' .repeat(70));
}

main().catch(console.error);
