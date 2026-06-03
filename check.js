const fs=require('fs');
const c=fs.readFileSync('public/index.html','utf8');
const lines=c.split('\n');
let opens=0;
lines.forEach((l,i)=>{
  if(l.includes('try {')) opens++;
  if(l.includes('catch(')) opens--;
  if(opens>0) console.log('L'+(i+1)+':'+opens+' '+l.trim().slice(0,60));
});
