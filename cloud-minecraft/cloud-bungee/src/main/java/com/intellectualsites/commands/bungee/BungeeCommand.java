//
// MIT License
//
// Copyright (c) 2020 Alexander Söderberg
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//
package com.intellectualsites.commands.bungee;

import com.intellectualsites.commands.arguments.CommandArgument;
import com.intellectualsites.commands.arguments.StaticArgument;
import com.intellectualsites.commands.exceptions.ArgumentParseException;
import com.intellectualsites.commands.exceptions.InvalidCommandSenderException;
import com.intellectualsites.commands.exceptions.InvalidSyntaxException;
import com.intellectualsites.commands.exceptions.NoPermissionException;
import com.intellectualsites.commands.exceptions.NoSuchCommandException;
import com.intellectualsites.commands.meta.SimpleCommandMeta;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

import javax.annotation.Nonnull;

public final class BungeeCommand<C> extends Command implements TabExecutor {

    private static final String MESSAGE_NO_PERMS =
              "I'm sorry, but you do not have permission to perform this command. "
            + "Please contact the server administrators if you believe that this is in error.";
    private static final String MESSAGE_UNKNOWN_COMMAND = "Unknown command. Type \"/help\" for help.";

    private final BungeeCommandManager<C> bungeeCommandManager;
    private final CommandArgument<C, ?> command;
    private final com.intellectualsites.commands.Command<C, SimpleCommandMeta> cloudCommand;

    @SuppressWarnings("unchecked")
    BungeeCommand(@Nonnull final com.intellectualsites.commands.Command<C, SimpleCommandMeta> cloudCommand,
                  @Nonnull final CommandArgument<C, ?> command,
                  @Nonnull final BungeeCommandManager<C> bungeeCommandManager) {
        super(command.getName(),
              cloudCommand.getCommandPermission(),
              ((StaticArgument<C>) command).getAlternativeAliases().toArray(new String[0]));
        this.command = command;
        this.bungeeCommandManager = bungeeCommandManager;
        this.cloudCommand = cloudCommand;
    }

    @Override
    public void execute(final CommandSender commandSender, final String[] strings) {
        /* Join input */
        final StringBuilder builder = new StringBuilder(this.command.getName());
        for (final String string : strings) {
            builder.append(" ").append(string);
        }
        this.bungeeCommandManager.executeCommand(this.bungeeCommandManager.getCommandSenderMapper().apply(commandSender),
                                                 builder.toString())
         .whenComplete(((commandResult, throwable) -> {
             if (throwable != null) {
                 if (throwable instanceof InvalidSyntaxException) {
                     commandSender.sendMessage(
                             new ComponentBuilder("Invalid Command Syntax. Correct command syntax is: ")
                                     .color(ChatColor.RED)
                                     .append("/")
                                     .color(ChatColor.GRAY)
                                     .append(((InvalidSyntaxException) throwable).getCorrectSyntax())
                                     .color(ChatColor.GRAY)
                                     .create()
                     );
                 } else if (throwable instanceof InvalidCommandSenderException) {
                     commandSender.sendMessage(new ComponentBuilder(throwable.getMessage())
                                                       .color(ChatColor.RED)
                                                       .create());
                 } else if (throwable instanceof NoPermissionException) {
                     commandSender.sendMessage(new ComponentBuilder(MESSAGE_NO_PERMS)
                                                       .color(ChatColor.WHITE)
                                                       .create());
                 } else if (throwable instanceof NoSuchCommandException) {
                     commandSender.sendMessage(new ComponentBuilder(MESSAGE_UNKNOWN_COMMAND)
                                                       .color(ChatColor.WHITE)
                                                       .create());
                 } else if (throwable instanceof ArgumentParseException) {
                     commandSender.sendMessage(new ComponentBuilder("Invalid Command Argument: ")
                                                        .color(ChatColor.GRAY)
                                                        .append(throwable.getCause().getMessage())
                                                        .create());
                 } else {
                     commandSender.sendMessage(new ComponentBuilder(throwable.getMessage()).create());
                     throwable.printStackTrace();
                 }
             }
         }));
    }

    @Override
    public Iterable<String> onTabComplete(final CommandSender sender,
                                          final String[] args) {
        final StringBuilder builder = new StringBuilder(this.command.getName());
        for (final String string : args) {
            builder.append(" ").append(string);
        }
        return this.bungeeCommandManager.suggest(this.bungeeCommandManager.getCommandSenderMapper().apply(sender),
                                                 builder.toString());
    }

}