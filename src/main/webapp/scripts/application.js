$(function(){
    var userName;
    $("form").submit(function(event){
        var form = $(this)

        var url = form.attr("action");
        $.ajax({
            type: "POST",
            url: url,
            data: form.serialize(), // serializes the form's elements.
            success: function(data) {
                $('#login_container').hide()
                $('#chat_container').show()
                userName = $('#login_field').val()
                //initUser()
                initSocket()
            }
        });

        return false;
        event.preventDefault();
    });
    function initUser() {
        $.ajax({
            type: "get",
            url: "roster",
            success: function(data) {
                var memberList = data.content;
                for(var i in memberList) {
                    var member = memberList[i]
                    var user = '<div class="chatListColumn" username="' + member.name + '" id="conv_filehelper">' +
                    '<div class="clicked" style="display:none;"></div>' +
                    '    <span class="unreadDot" style="display:none">0</span>' +
                    '    <span class="unreadDotS" style="display:none"></span>' +
                    '    <div class="avatar_wrap">' +
                    '        <img class="avatar" src=""  click1="showProfile@.chatListColumn">' +
                    '        </div>' +
                    '        <div class="extend">' +
                    '            <p class="time">15:18</p>' +
                    '            <div class="edited">' +
                    '                <i class="editedIcon"></i>' +
                    '            </div>' +
                    '            <div class="mute" style="display:none;"></div>' +
                    '        </div>' +
                    '        <div class="info">' +
                    '            <div class="nickName">' +
                    '                <div class="left name" style="">' + member.nickName + '</div>' +
                    '                <div class="clr"></div>' +
                    '            </div>' +
                    '            <div class="descWrapper">' +
                    '                <p class="desc"></p>' +
                    '            </div>' +
                    '        </div>' +
                    '        <div class="clr"></div>' +
                    '</div'
                    $('#conversationContainer').append(user)
                }
                initSocket()
            }
        });
    }
    var socket1;
    var chatList = {};
    var currentChatUserName = "mqshen";
    function sendMessage() {
        var message = $('#textInput').val();
        var messageHtml = '<div class="chatItem me" un="item_1406990279434">' +
            '    <div class="time">' +
            '        <span class="timeBg left"></span>' +
            '        22:37' +
            '        <span class="timeBg right"></span>' +
            '    </div>' +
            '    <div class="chatItemContent">' +
            '        <img class="avatar" src=""  un="avatar_goldratio" title="goldenratio" click="showProfile" username="goldratio">' +
            '        <div class="cloud cloudText" un="cloud_1024326766" msgid="1024326766">' +
            '            <div class="cloudPannel" style="">' +
            '                <div class="sendStatus">   </div>' +
            '                <div class="cloudBody">' +
            '                    <div class="cloudContent">' +
            '                        <pre style="white-space:pre-wrap">' + message + '</pre>' +
            '                    </div>' +
            '                </div>' +
            '                <div class="cloudArrow "></div>' +
            '            </div>' +
            '        </div>' +
            '    </div>' +
            '</div>'

        $('#chat_chatmsglist').append(messageHtml)
        socket1.emit('chat', {"fromUserName": userName, "toUserName": currentChatUserName, content: message, type: 1, clientMsgId: 123123});
        //socket1.emit('create', 'goldratio', 'mqshen')
        //socket1.emit('chatRoom', {"fromUserName": userName, "toUserName": "42ff77db28bded20aabfb0f32ceabb49@room", content: message, type: 1, clientMsgId: 123123})
        $('#textInput').val('');
    }
    function receiveMessage(response) {
        if(response.name === "chat") {
            var message = response.content
            if(chatList[message.fromUserName] === undefined) {
                chatList[message.fromUserName] = []
            }
            chatList[message.fromUserName].push(message)
            if(currentChatUserName === message.fromUserName) {
                renderMessage(message)
            }
        }
        else {
            for (var i in response.content) {
                var content = response.content[i]
                if (content.syncType === "user") {
                    if(content.operation === "add") {
                        addMember(content.content)
                    }
                }
            }
            //initMembers(response.content)
        }
    }

    function addMember(member) {
        var user = '<div class="chatListColumn" username="' + member.userName + '" id="conv_filehelper">' +
            '<div class="clicked" style="display:none;"></div>' +
            '    <span class="unreadDot" style="display:none">0</span>' +
            '    <span class="unreadDotS" style="display:none"></span>' +
            '    <div class="avatar_wrap">' +
            '        <img class="avatar" src=""  click1="showProfile@.chatListColumn">' +
            '        </div>' +
            '        <div class="extend">' +
            '            <p class="time">15:18</p>' +
            '            <div class="edited">' +
            '                <i class="editedIcon"></i>' +
            '            </div>' +
            '            <div class="mute" style="display:none;"></div>' +
            '        </div>' +
            '        <div class="info">' +
            '            <div class="nickName">' +
            '                <div class="left name" style="">' + member.nickName + '</div>' +
            '                <div class="clr"></div>' +
            '            </div>' +
            '            <div class="descWrapper">' +
            '                <p class="desc"></p>' +
            '            </div>' +
            '        </div>' +
            '        <div class="clr"></div>' +
            '</div'
        $('#conversationContainer').append(user)

    }
    function initMembers(memberList) {
        for(var i in memberList) {
            var member = memberList[i]
            var user = '<div class="chatListColumn" username="' + member.name + '" id="conv_filehelper">' +
            '<div class="clicked" style="display:none;"></div>' +
            '    <span class="unreadDot" style="display:none">0</span>' +
            '    <span class="unreadDotS" style="display:none"></span>' +
            '    <div class="avatar_wrap">' +
            '        <img class="avatar" src=""  click1="showProfile@.chatListColumn">' +
            '        </div>' +
            '        <div class="extend">' +
            '            <p class="time">15:18</p>' +
            '            <div class="edited">' +
            '                <i class="editedIcon"></i>' +
            '            </div>' +
            '            <div class="mute" style="display:none;"></div>' +
            '        </div>' +
            '        <div class="info">' +
            '            <div class="nickName">' +
            '                <div class="left name" style="">' + member.nickName + '</div>' +
            '                <div class="clr"></div>' +
            '            </div>' +
            '            <div class="descWrapper">' +
            '                <p class="desc"></p>' +
            '            </div>' +
            '        </div>' +
            '        <div class="clr"></div>' +
            '</div'
            $('#conversationContainer').append(user)
        }
    }
    function renderMessage(message) {
        var messageHtml = '<div class="chatItem you" un="item_1406990279434">' +
            '    <div class="time">' +
            '        <span class="timeBg left"></span>' +
            '        22:37' +
            '        <span class="timeBg right"></span>' +
            '    </div>' +
            '    <div class="chatItemContent">' +
            '        <img class="avatar" src=""  un="avatar_goldratio" title="goldenratio" click="showProfile" username="goldratio">' +
            '        <div class="cloud cloudText" un="cloud_1024326766" msgid="1024326766">' +
            '            <div class="cloudPannel" style="">' +
            '                <div class="sendStatus">   </div>' +
            '                <div class="cloudBody">' +
            '                    <div class="cloudContent">' +
            '                        <pre style="white-space:pre-wrap">' + message.content + '</pre>' +
            '                    </div>' +
            '                </div>' +
            '                <div class="cloudArrow "></div>' +
            '            </div>' +
            '        </div>' +
            '    </div>' +
            '</div>'
        $('#chat_chatmsglist').append(messageHtml)
    }

    function initSocket() {
        socket1 = io.connect('//localhost:8080/');
        socket1.on('connect', function() {
            socket1.emit("sync", 0);
            console.log("connected!");
            $('#messages').append('<li>' + 'connected' + '</li>');
        });
        socket1.on('error', function(errormsg) {
            console.error(errormsg);
        });
        socket1.on('message', function(msg) {
            receiveMessage($.parseJSON(msg))
        });
        socket1.on('disconnect', function() {
            console.log('The client has disconnected!');
        });
        $('#sendMessage').bind('click', function(e){
            sendMessage();
        })
    }
    $(document).on('click.chat.data-api', '.chatListColumn', function (e) {
        var $btn = $(e.target)
        if (!$btn.hasClass('chatListColumn'))
            $btn = $btn.closest('.chatListColumn')
        $btn.siblings('.active').removeClass("active")
        $btn.addClass("active")
        currentChatUserName = $btn.attr("username")
        $('#messagePanelTitle').text(currentChatUserName)
        e.preventDefault()
    })

})
