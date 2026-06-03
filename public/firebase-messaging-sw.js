// v1779478005615
importScripts('https://www.gstatic.com/firebasejs/11.3.1/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/11.3.1/firebase-messaging-compat.js');

firebase.initializeApp({
  apiKey: "AIzaSyCNyyoZmmXv3JYF7bFRmouj9MewSxjT24Y",
  authDomain: "threadwise-8b8f5.firebaseapp.com",
  projectId: "threadwise-8b8f5",
  storageBucket: "threadwise-8b8f5.firebasestorage.app",
  messagingSenderId: "865341567672",
  appId: "1:865341567672:web:03374e4331de7b46c57a4d"
});

const messaging = firebase.messaging();

messaging.onBackgroundMessage(payload => {
  self.registration.showNotification(payload.notification.title, {
    body: payload.notification.body,
    icon: '/icon.png'
  });
});
