const fs=require('fs');
let c=fs.readFileSync('public/index.html','utf8');
const marker='// Save messages to localStorage cache';
const idx=c.indexOf(marker);
const insertAt=c.lastIndexOf('});',idx);
c=c.slice(0,insertAt+3)+'\n        } catch(e) {}'+c.slice(insertAt+3);
fs.writeFileSync('public/index.html',c);
console.log('Done at index',insertAt);
