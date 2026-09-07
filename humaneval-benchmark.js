/**
 * HumanEval标准编程能力评测
 * 
 * 从HumanEval数据集中选取10道代表性题目
 * 自动执行测试用例判分，计算pass@1
 * 
 * 对比: Lion-Code vs DSH (均使用MiMo v2.5)
 */
const https = require('https');
const fs = require('fs');
const { execSync } = require('child_process');

// API Key从环境变量读取，切勿硬编码提交
const MIMO_API_KEY = process.env.MIMO_API_KEY || '';
const MIMO_BASE_URL = 'https://api.xiaomimimo.com/v1';
const MODEL = 'mimo-v2.5';

// ==================== HumanEval题目精选 ====================
// 来源: https://github.com/openai/human-eval
const HUMANEVAL_PROBLEMS = [
    {
        id: "HumanEval/0",
        name: "has_close_elements",
        prompt: `from typing import List

def has_close_elements(numbers: List[float], threshold: float) -> bool:
    """Check if in given list of numbers, are any two numbers closer to each other than
    given threshold.
    >>> has_close_elements([1.0, 2.0, 3.0], 0.5)
    False
    >>> has_close_elements([1.0, 2.8, 3.0, 4.0, 5.0, 2.0], 0.3)
    True
    """
`,
        tests: [
            `assert has_close_elements([1.0, 2.0, 3.9, 4.0, 5.0, 2.2], 0.3) == True`,
            `assert has_close_elements([1.0, 2.0, 3.9, 4.0, 5.0, 2.2], 0.05) == False`,
            `assert has_close_elements([1.0, 2.0, 5.9, 4.0, 5.0], 0.95) == True`,
            `assert has_close_elements([1.0, 2.0, 5.9, 4.0, 5.0], 0.8) == False`,
            `assert has_close_elements([1.0, 2.0, 3.0, 4.0, 5.0], 2.0) == True`,
            `assert has_close_elements([], 1.0) == False`
        ]
    },
    {
        id: "HumanEval/1",
        name: "separate_paren_groups",
        prompt: `from typing import List

def separate_paren_groups(paren_string: str) -> List[str]:
    """Input to this function is a string containing multiple groups of nested parentheses. Your goal is to
    separate those groups into separate strings and return the list of those.
    Separate groups are balanced (each open brace is properly closed) and not nested within each other
    Ignore any spaces in the input string.
    >>> separate_paren_groups('( ) (( )) (( )( ))')
    ['()', '(())', '(()())']
    """
`,
        tests: [
            `assert separate_paren_groups('(()()) ((())) () (()())()') == ['(()())', '((()))', '()', '(()())()']`,
            `assert separate_paren_groups('() (()) ((())) (((())))') == ['()', '(())', '((()))', '(((())))']`,
            `assert separate_paren_groups('(()(()))') == ['(()(()))']`,
            `assert separate_paren_groups('( ) (( )) (( )( ))') == ['()', '(())', '(()())']`
        ]
    },
    {
        id: "HumanEval/2",
        name: "truncate_number",
        prompt: `def truncate_number(number: float) -> float:
    """Given a positive floating point number, it can be decomposed into an integer part and a decimal part.
    Return the decimal part of the number.
    >>> truncate_number(3.5)
    0.5
    """
`,
        tests: [
            `assert truncate_number(3.5) == 0.5`,
            `assert truncate_number(1.25) == 0.25`,
            `assert truncate_number(123.456) == abs(123.456 - 123)`
        ]
    },
    {
        id: "HumanEval/5",
        name: "intersperse",
        prompt: `from typing import List

def intersperse(numbers: List[int], delimeter: int) -> List[int]:
    """Insert a number 'delimeter' between every two consecutive elements of input list numbers.
    >>> intersperse([], 4)
    []
    >>> intersperse([1, 2, 3], 4)
    [1, 4, 2, 4, 3]
    """
`,
        tests: [
            `assert intersperse([], 7) == []`,
            `assert intersperse([5, 6, 3, 2], 8) == [5, 8, 6, 8, 3, 8, 2]`,
            `assert intersperse([2, 2, 2], 2) == [2, 2, 2, 2, 2]`
        ]
    },
    {
        id: "HumanEval/10",
        name: "is_palindrome",
        prompt: `def is_palindrome(string: str) -> bool:
    """Test if given string is a palindrome."""
    return string == string[::-1]

def make_palindrome(string: str) -> str:
    """Find the shortest palindrome that begins with a supplied string.
    Algorithm idea is simple:
    - Find the longest postfix of supplied string that is a palindrome.
    - Append to the end of the string reverse of a string prefix that comes before the palindromic suffix.
    >>> make_palindrome('')
    ''
    >>> make_palindrome('cat')
    'catac'
    >>> make_palindrome('cata')
    'catac'
    """
`,
        tests: [
            `assert make_palindrome('') == ''`,
            `assert make_palindrome('x') == 'x'`,
            `assert make_palindrome('xyz') == 'xyzyx'`,
            `assert make_palindrome('xyx') == 'xyx'`,
            `assert make_palindrome('jerry') == 'jerryrrej'`
        ]
    },
    {
        id: "HumanEval/11",
        name: "string_xor",
        prompt: `def string_xor(a: str, b: str) -> str:
    """Input are two strings a and b consisting only of 1s and 0s.
    Perform binary XOR on these inputs and return result also as a string.
    >>> string_xor('010', '110')
    '100'
    """
`,
        tests: [
            `assert string_xor('111111', '101010') == '010101'`,
            `assert string_xor('1', '1') == '0'`,
            `assert string_xor('0101', '0000') == '0101'`
        ]
    },
    {
        id: "HumanEval/15",
        name: "string_sequence",
        prompt: `def string_sequence(n: int) -> str:
    """Return a string containing space-delimited numbers starting from 0 up to n inclusive.
    >>> string_sequence(0)
    '0'
    >>> string_sequence(5)
    '0 1 2 3 4 5'
    """
`,
        tests: [
            `assert string_sequence(0) == '0'`,
            `assert string_sequence(3) == '0 1 2 3'`,
            `assert string_sequence(10) == '0 1 2 3 4 5 6 7 8 9 10'`
        ]
    },
    {
        id: "HumanEval/20",
        name: "find_closest_elements",
        prompt: `from typing import List, Tuple

def find_closest_elements(numbers: List[float]) -> Tuple[float, float]:
    """ From a supplied list of numbers (of length at least two) select and return two that are the closest to each
    other and return them in order (smaller number, larger number).
    >>> find_closest_elements([1.0, 2.0, 3.0, 4.0, 5.0, 2.2])
    (2.0, 2.2)
    >>> find_closest_elements([1.0, 2.0, 3.0, 4.0, 5.0, 2.0])
    (2.0, 2.0)
    """
`,
        tests: [
            `assert find_closest_elements([1.0, 2.0, 3.9, 4.0, 5.0, 2.2]) == (3.9, 4.0)`,
            `assert find_closest_elements([1.0, 2.0, 5.9, 4.0, 5.0]) == (5.0, 5.9)`,
            `assert find_closest_elements([1.0, 2.0, 3.0, 4.0, 5.0, 2.2]) == (2.0, 2.2)`,
            `assert find_closest_elements([1.0, 2.0, 3.0, 4.0, 5.0, 2.0]) == (2.0, 2.0)`
        ]
    },
    {
        id: "HumanEval/25",
        name: "factorize",
        prompt: `from typing import List

def factorize(n: int) -> List[int]:
    """ Return list of prime factors of given integer in the order from smallest to largest.
    Each of the factors should be listed number of times corresponding to how many times it appears in factorization.
    Input number should be equal to the product of all factors
    >>> factorize(8)
    [2, 2, 2]
    >>> factorize(25)
    [5, 5]
    >>> factorize(70)
    [2, 5, 7]
    """
`,
        tests: [
            `assert factorize(2) == [2]`,
            `assert factorize(4) == [2, 2]`,
            `assert factorize(8) == [2, 2, 2]`,
            `assert factorize(57) == [3, 19]`,
            `assert factorize(3249) == [3, 3, 19, 19]`,
            `assert factorize(25) == [5, 5]`,
            `assert factorize(70) == [2, 5, 7]`
        ]
    },
    {
        id: "HumanEval/32",
        name: "find_zero",
        prompt: `import math
from typing import List

def poly(xs: List[float], x: float):
    """Evaluate polynomial with coefficients xs at point x."""
    return sum([coeff * math.pow(x, i) for i, coeff in enumerate(xs)])

def find_zero(xs: List[float]):
    """ xs are coefficients of a polynomial.
    find_zero find x such that poly(x) = 0.
    find_zero returns only only zero point, even if there are many.
    Moreover, find_zero only takes list xs having even number of coefficients
    and largest non zero coefficient as it guarantees a solution.
    >>> round(find_zero([1, 2]), 2) # f(x) = 1 + 2x
    -0.5
    >>> round(find_zero([-6, 11, -6, 1]), 2) # (x - 1) * (x - 2) * (x - 3) = -6 + 11x - 6x^2 + x^3
    1.0
    """
`,
        tests: [
            `import math`,
            `assert math.fabs(find_zero([1, 2]) - (-0.5)) < 1e-3`,
            `assert math.fabs(find_zero([-6, 11, -6, 1]) - 1.0) < 1e-3`
        ]
    }
];

// ==================== System Prompts ====================
const LIONCODE_SYSTEM = `你是Lion-Code Agent，一个强大的AI编程助手。

核心架构特点：
- 一切皆插件：51个插件（4 Skill + 47 Tool）
- 流式工具执行：识别到工具调用块就调度执行
- 事件溯源：完整记录每轮思考、工具调用、返回结果
- 双协议适配器：OpenAI兼容 + Anthropic原生

你擅长写出高质量、线程安全、边界处理完善的代码。
请直接输出函数实现代码，不要输出解释文字。`;

const DSH_SYSTEM = `你是DeepSeek Harness Agent，一个插件化的AI编程助手。

核心架构：
- Cordis微内核：一切皆插件
- 技能包系统：Skill技能包实现为插件
- 工具系统：Function工具实现为插件
- 服务注入、事件总线、插件热加载/卸载

请直接输出函数实现代码，不要输出解释文字。`;

// ==================== API调用 ====================
function callMimo(systemPrompt, userPrompt) {
    return new Promise((resolve, reject) => {
        const body = JSON.stringify({
            model: MODEL,
            messages: [
                { role: 'system', content: systemPrompt },
                { role: 'user', content: userPrompt + '\n\n请直接输出完整的函数实现代码。只输出代码，不要任何解释。' }
            ],
            temperature: 0.0,
            max_tokens: 2048,
            stream: false
        });

        const url = new URL(MIMO_BASE_URL + '/chat/completions');
        const options = {
            hostname: url.hostname,
            port: 443,
            path: url.pathname,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${MIMO_API_KEY}`,
                'Content-Length': Buffer.byteLength(body)
            },
            timeout: 120000
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
                        reject(new Error('API error: ' + JSON.stringify(json)));
                    }
                } catch (e) {
                    reject(new Error('Parse error: ' + e.message));
                }
            });
        });

        req.on('error', reject);
        req.on('timeout', () => { req.destroy(); reject(new Error('Timeout')); });
        req.write(body);
        req.end();
    });
}

// ==================== 代码提取和测试 ====================
function extractPythonCode(response) {
    // 从markdown代码块中提取Python代码
    const codeBlockMatch = response.match(/```python\s*\n([\s\S]*?)```/);
    if (codeBlockMatch) return codeBlockMatch[1].trim();
    
    const genericBlockMatch = response.match(/```\s*\n([\s\S]*?)```/);
    if (genericBlockMatch) return genericBlockMatch[1].trim();
    
    // 如果没有代码块，返回整个响应（去除前后非代码内容）
    return response.trim();
}

function runPythonTest(code, tests, funcName) {
    const testCode = code + '\n\n' + tests.join('\n') + '\n';
    const tempFile = `temp_test_${funcName}.py`;
    
    try {
        fs.writeFileSync(tempFile, testCode, 'utf-8');
        execSync(`python ${tempFile}`, { timeout: 10000, stdio: 'pipe' });
        return { pass: true, error: null };
    } catch (e) {
        return { pass: false, error: e.stderr?.toString()?.split('\n').slice(-5).join('\n') || e.message };
    } finally {
        try { fs.unlinkSync(tempFile); } catch {}
    }
}

// ==================== 主测试流程 ====================
async function main() {
    console.log('=' .repeat(70));
    console.log('  HumanEval标准编程能力评测');
    console.log('  模型: 小米MiMo v2.5');
    console.log('  题目数: ' + HUMANEVAL_PROBLEMS.length);
    console.log('=' .repeat(70));

    // 检查Python
    try {
        const pyVersion = execSync('python --version', { encoding: 'utf-8' }).trim();
        console.log('  Python: ' + pyVersion);
    } catch {
        console.log('  ❌ Python未安装，无法运行自动判分');
        return;
    }

    const results = { lioncode: { pass: 0, fail: 0, details: [] }, dsh: { pass: 0, fail: 0, details: [] } };
    let totalTokens = { lioncode: 0, dsh: 0 };

    for (let i = 0; i < HUMANEVAL_PROBLEMS.length; i++) {
        const problem = HUMANEVAL_PROBLEMS[i];
        console.log(`\n[${ i + 1}/${HUMANEVAL_PROBLEMS.length}] ${problem.id}: ${problem.name}`);
        console.log('-' .repeat(50));

        // Lion-Code测试
        process.stdout.write('  🦁 Lion-Code: ');
        try {
            const lionResp = await callMimo(LIONCODE_SYSTEM, problem.prompt);
            totalTokens.lioncode += lionResp.usage?.total_tokens || 0;
            const lionCode = extractPythonCode(lionResp.content);
            const lionResult = runPythonTest(lionCode, problem.tests, problem.name + '_lion');
            
            if (lionResult.pass) {
                console.log('✅ PASS');
                results.lioncode.pass++;
                results.lioncode.details.push({ id: problem.id, name: problem.name, result: 'PASS' });
            } else {
                console.log('❌ FAIL');
                console.log('    错误: ' + (lionResult.error || '').substring(0, 100));
                results.lioncode.fail++;
                results.lioncode.details.push({ id: problem.id, name: problem.name, result: 'FAIL', error: lionResult.error });
            }
        } catch (e) {
            console.log('❌ ERROR: ' + e.message.substring(0, 80));
            results.lioncode.fail++;
            results.lioncode.details.push({ id: problem.id, name: problem.name, result: 'ERROR', error: e.message });
        }

        // DSH测试
        process.stdout.write('  🔷 DSH:       ');
        try {
            const dshResp = await callMimo(DSH_SYSTEM, problem.prompt);
            totalTokens.dsh += dshResp.usage?.total_tokens || 0;
            const dshCode = extractPythonCode(dshResp.content);
            const dshResult = runPythonTest(dshCode, problem.tests, problem.name + '_dsh');
            
            if (dshResult.pass) {
                console.log('✅ PASS');
                results.dsh.pass++;
                results.dsh.details.push({ id: problem.id, name: problem.name, result: 'PASS' });
            } else {
                console.log('❌ FAIL');
                console.log('    错误: ' + (dshResult.error || '').substring(0, 100));
                results.dsh.fail++;
                results.dsh.details.push({ id: problem.id, name: problem.name, result: 'FAIL', error: dshResult.error });
            }
        } catch (e) {
            console.log('❌ ERROR: ' + e.message.substring(0, 80));
            results.dsh.fail++;
            results.dsh.details.push({ id: problem.id, name: problem.name, result: 'ERROR', error: e.message });
        }
    }

    // ==================== 最终报告 ====================
    const lionPassRate = (results.lioncode.pass / HUMANEVAL_PROBLEMS.length * 100).toFixed(1);
    const dshPassRate = (results.dsh.pass / HUMANEVAL_PROBLEMS.length * 100).toFixed(1);

    console.log('\n' + '=' .repeat(70));
    console.log('  📊 HumanEval评测结果');
    console.log('=' .repeat(70));
    console.log(`\n  🦁 Lion-Code:  ${results.lioncode.pass}/${HUMANEVAL_PROBLEMS.length} 通过  (pass@1 = ${lionPassRate}%)`);
    console.log(`  🔷 DSH:        ${results.dsh.pass}/${HUMANEVAL_PROBLEMS.length} 通过  (pass@1 = ${dshPassRate}%)`);
    console.log(`\n  Token消耗:     Lion-Code ${totalTokens.lioncode} | DSH ${totalTokens.dsh}`);

    console.log('\n  详细结果:');
    console.log('  ' + '-'.repeat(60));
    console.log('  题目ID'.padEnd(25) + 'Lion-Code'.padEnd(12) + 'DSH');
    console.log('  ' + '-'.repeat(60));
    
    for (let i = 0; i < HUMANEVAL_PROBLEMS.length; i++) {
        const lion = results.lioncode.details[i];
        const dsh = results.dsh.details[i];
        const lionIcon = lion?.result === 'PASS' ? '✅' : '❌';
        const dshIcon = dsh?.result === 'PASS' ? '✅' : '❌';
        console.log(`  ${lion?.name?.padEnd(23) || ''} ${lionIcon}          ${dshIcon}`);
    }

    console.log('\n  ' + '-'.repeat(60));
    const winner = results.lioncode.pass > results.dsh.pass ? '🦁 Lion-Code' : 
                   results.dsh.pass > results.lioncode.pass ? '🔷 DSH' : '🤝 平局';
    console.log(`  🏆 胜出: ${winner}`);
    console.log('=' .repeat(70));

    // 保存报告
    const report = {
        benchmark: 'HumanEval',
        model: MODEL,
        problems: HUMANEVAL_PROBLEMS.length,
        lioncode: { pass: results.lioncode.pass, passRate: lionPassRate + '%', tokens: totalTokens.lioncode },
        dsh: { pass: results.dsh.pass, passRate: dshPassRate + '%', tokens: totalTokens.dsh },
        details: { lioncode: results.lioncode.details, dsh: results.dsh.details }
    };
    fs.writeFileSync('humaneval-report.json', JSON.stringify(report, null, 2), 'utf-8');
    console.log('\n  📄 完整报告已保存: humaneval-report.json');
}

main().catch(console.error);
