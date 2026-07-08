const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
const fs = require('fs');

puppeteer.use(StealthPlugin());

(async () => {
    // Launch REAL browser (headless: false) on Windows server to fool Cloudflare
    const browser = await puppeteer.launch({ 
        headless: false, 
        args: [
            '--no-sandbox', 
            '--disable-setuid-sandbox', 
            '--window-size=1280,720',
            '--disable-blink-features=AutomationControlled'
        ] 
    });
    
    const page = await browser.newPage();
    await page.setViewport({ width: 1280, height: 720 });
    let token = "";

    await page.setRequestInterception(true);
    page.on('request', request => {
        // 1. Check HTTP Headers
        const headers = request.headers();
        if (headers['authorization']) {
            token = headers['authorization'].replace('Bearer ', '').trim();
        }
        
        // 2. Check POST Body
        const postData = request.postData();
        if (request.method() === 'POST' && postData) {
            if (postData.includes('authorization=')) {
                const params = new URLSearchParams(postData);
                if (params.get('authorization')) {
                    token = params.get('authorization');
                }
            } else {
                try {
                    const json = JSON.parse(postData);
                    if (json.authorization) token = json.authorization;
                } catch(e) {}
            }
        }
        request.continue();
    });

    console.log('Visiting Kuramanime website...');
    
    try {
        // Give 90 seconds just in case Cloudflare Turnstile takes time to resolve
        await page.goto('https://v18.kuramanime.ing/anime/5025/grand-blue-season-3/episode/1', { 
            waitUntil: 'networkidle2', 
            timeout: 90000 
        });
        
        // Wait an extra 10 seconds for Cloudflare challenge & JS execution
        await new Promise(r => setTimeout(r, 10000));
        
    } catch (e) {
        console.log('Warning: Navigation timeout, checking if token was captured...');
    }

    if (token && token.length > 10) {
        fs.writeFileSync('kurama_token.txt', token);
        console.log(`SUCCESS: Token retrieved -> ${token.substring(0, 15)}...`);
    } else {
        console.log('FAIL: Token not found.');
        await page.screenshot({ path: 'error.png', fullPage: true });
        console.log('Failure screenshot saved as error.png');
        process.exit(1);
    }

    await browser.close();
})();
