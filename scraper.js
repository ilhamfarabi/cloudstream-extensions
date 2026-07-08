const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
const fs = require('fs');

puppeteer.use(StealthPlugin());

(async () => {
    const browser = await puppeteer.launch({ 
        headless: "new", 
        args: ['--no-sandbox', '--disable-setuid-sandbox'] 
    });
    
    const page = await browser.newPage();
    let token = "";

    await page.setRequestInterception(true);
    page.on('request', request => {
        const headers = request.headers();
        if (headers['authorization'] && headers['authorization'].includes('Bearer')) {
            token = headers['authorization'].replace('Bearer ', '').trim();
            console.log('Token successfully captured!');
        }
        request.continue();
    });

    console.log('Visiting Kuramanime website...');
    
    try {
        await page.goto('https://v18.kuramanime.ing/anime/5025/grand-blue-season-3/episode/1', { 
            waitUntil: 'networkidle2', 
            timeout: 60000 
        });
    } catch (e) {
        console.log('Navigation timeout (Ignore if token already received).');
    }

    if (token) {
        fs.writeFileSync('kurama_token.txt', token);
        console.log('Token successfully saved to kurama_token.txt');
    } else {
        console.log('FAIL: Token not found.');
        process.exit(1);
    }

    await browser.close();
})();
