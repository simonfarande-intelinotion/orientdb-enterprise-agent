'use strict';


angular.module('webappApp').factory("ChatService", function ($rootScope, $location,$timeout ,User, Organization) {


  var charSocketWrapper = {
    socket: null,
    init: function (callback) {
      this.socket = new WebSocket(WEBSOCKET);
      callback();
    },
    send: function (msg) {
      this.socket.send(msg);
    }
  }
  var chatService = {
    connected: false,
    clients: [],
    notify: function (msg) {
      if (!("Notification" in window)) {
        alert("This browser does not support desktop notification");
      }

      // Let's check if the user is okay to get some notification
      else if (Notification.permission === "granted") {
        // If it's okay let's create a notification
        var notification = new Notification("Room " + this.getClientName(msg.clientId), {body: msg.sender.name + ": " + msg.body});
        notification.onclick = function () {
          $location.path('rooms/' + msg.clientId);
        }
      }

      // Otherwise, we need to ask the user for permission
      // Note, Chrome does not implement the permission static property
      // So we have to check for NOT 'denied' instead of 'default'
      else if (Notification.permission !== 'denied') {
        Notification.requestPermission(function (permission) {
          // If the user is okay, let's create a notification
          if (permission === "granted") {
            var notification = new Notification("Room " + this.getClientName(msg.clientId), {body: msg.body});
          }
        });
      }
    },
    send: function (msg) {
      charSocketWrapper.send(msg);
    },
    getClientName: function (clientId) {
      var name = ''
      this.clients.forEach(function (c) {
        if (c.clientId == clientId) name = c.name;
      });
      return name;
    }

  }

  var poll = function () {
    $timeout(function () {
      if (!chatService.connected) {
        console.log("Reconnecting to chat service! ")
        charSocketWrapper.init(initializer)
      }
      poll();
    }, 1000);
  }

  function initializer() {

    charSocketWrapper.socket.onopen = function () {
      console.log("Connected to chat service! ")
      chatService.connected = true;
      User.whoami().then(function (data) {
        chatService.currentUser = data;
        Organization.all("rooms").getList().then(function (data) {
          chatService.clients = data.plain();
          var msg = {
            "action": "join",
            "rooms": []
          }
          chatService.clients.forEach(function (c) {
            if (!c.timestamp) c.timestamp = 0;
            msg.rooms.push(c.clientId);
          })
          chatService.send(JSON.stringify(msg));
        });
      })
    };
    charSocketWrapper.socket.onmessage = function (evt) {
      var msg = JSON.parse(evt.data);
      if (msg.sender.name != chatService.currentUser.name) {
        chatService.notify(msg);
        $rootScope.$broadcast('msg-received', msg);
      }
    };
    charSocketWrapper.socket.onclose = function () {
      console.log("Disconnected from chat service!")
      chatService.connected = false;
    };

  }

  charSocketWrapper.init(initializer)

  poll();

  return chatService;
}).run(function(ChatService){

});


