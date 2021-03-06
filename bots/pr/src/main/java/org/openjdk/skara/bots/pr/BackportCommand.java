/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.forge.HostedCommit;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import java.io.PrintWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.time.format.DateTimeFormatter;

public class BackportCommand implements CommandHandler {
    private void showHelp(PrintWriter reply) {
        reply.println("Usage: `/backport <repository> [<branch>]`");
    }

    @Override
    public String description() {
        return "Create a backport";
    }

    @Override
    public boolean allowedInCommit() {
        return true;
    }

    @Override
    public boolean allowedInPullRequest() {
        return false;
    }

    @Override
    public void handle(PullRequestBot bot, HostedCommit commit, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        var username = command.user().username();
        if (censusInstance.contributor(command.user()).isEmpty()) {
            reply.println("@" + username + " only OpenJDK [contributors](https://openjdk.java.net/bylaws#contributor) can use the `/backport` command");
            return;
        }

        var args = command.args();
        if (args.isBlank()) {
            showHelp(reply);
            return;
        }

        var parts = args.split(" ");
        if (parts.length > 2) {
            showHelp(reply);
            return;
        }

        var forge = bot.repo().forge();
        var repoName = parts[0].replace("http://", "")
                               .replace("https://", "")
                               .replace(forge.hostname() + "/", "");
        var currentRepoName = bot.repo().name();
        if (!currentRepoName.equals(repoName) && !repoName.contains("/")) {
            var group = bot.repo().name().split("/")[0];
            repoName = group + "/" + repoName;
        }

        var targetRepo = forge.repository(repoName);
        if (targetRepo.isEmpty()) {
            reply.println("@" + username + " the target repository `" + repoName + "` does not exist");
            return;
        }

        var branchName = parts.length == 2 ? parts[1] : "master";
        var targetBranches = targetRepo.get().branches();
        if (targetBranches.stream().noneMatch(b -> b.name().equals(branchName))) {
            reply.println("@" + username + " the target branch `" + branchName + "` does not exist");
            return;
        }

        try {
            var hash = commit.hash();
            var fork = bot.writeableForkOf(targetRepo.get());
            var localRepoDir = scratchPath.resolve("backport-command")
                                          .resolve(repoName)
                                          .resolve("fork");
            var localRepo = bot.hostedRepositoryPool()
                               .orElseThrow(() -> new IllegalStateException("Missing repository pool for PR bot"))
                               .materialize(fork, localRepoDir);
            var fetchHead = localRepo.fetch(bot.repo().url(), hash.hex());
            localRepo.checkout(new Branch(branchName));
            var head = localRepo.head();
            var backportBranch = localRepo.branch(head, "backport-" + hash.abbreviate());
            localRepo.checkout(backportBranch);
            var didApply = localRepo.cherryPick(fetchHead);
            if (!didApply) {
                var lines = new ArrayList<String>();
                lines.add("@" + username + " :warning: could not backport `" + hash.abbreviate() + "` to " +
                          "[" + repoName + "](" + targetRepo.get().webUrl() + "] due to conflicts in the following files:");
                lines.add("");
                var unmerged = localRepo.status()
                                        .stream()
                                        .filter(e -> e.status().isUnmerged())
                                        .map(e -> e.target().path().orElseGet(() -> e.source().path().orElseThrow()))
                                        .collect(Collectors.toList());
                for (var path : unmerged) {
                    lines.add("- " + path.toString());
                }
                lines.add("");
                lines.add("To manually resolve these conflicts run the following commands in your personal fork of [" + repoName + "](" + targetRepo.get().webUrl() + "):");
                lines.add("");
                lines.add("```");
                lines.add("$ git checkout -b " + backportBranch.name());
                lines.add("$ git fetch " + bot.repo().webUrl() + " " + hash.hex());
                lines.add("$ git cherry-pick --no-commit " + hash.hex());
                lines.add("$ # Resolve conflicts");
                lines.add("$ git add files/with/resolved/conflicts");
                lines.add("$ git commit -m 'Backport " + hash.hex() + "'");
                lines.add("```");
                lines.add("");
                lines.add("Once you have resolved the conflicts as explained above continue with creating a pull request towards the [" + repoName + "](" + targetRepo.get().webUrl() + ") with the title \"Backport " + hash.hex() + "\".");

                reply.println(String.join("\n", lines));
                localRepo.reset(head, true);
                return;
            }

            var backportHash = localRepo.commit("Backport " + hash.hex(), "duke", "duke@openjdk.org");
            localRepo.push(backportHash, fork.url(), backportBranch.name(), true);
            var message = CommitMessageParsers.v1.parse(commit);
            var formatter = DateTimeFormatter.ofPattern("d MMM uuuu");
            var lines = new ArrayList<String>();
            lines.add("Hi all,");
            lines.add("");
            lines.add("this is an _automatically_ generated pull request containing a backport of " +
                      "[" + hash.abbreviate() + "](" + commit.url() + ") as requested by " +
                      "@" + username);
            lines.add("");
            var info = "The commit being backported was authored by " + commit.author().name() + " on " +
                        commit.committed().format(formatter);
            if (message.reviewers().isEmpty()) {
                info += " and had no reviewers";
            } else {
                var reviewers = message.reviewers()
                                       .stream()
                                       .map(r -> censusInstance.census().contributor(r))
                                       .map(c -> {
                                           var link = "[" + c.username() + "](https://openjdk.java.net/census#" +
                                                      c.username() + ")";
                                           return c.fullName().isPresent() ?
                                                    c.fullName() + " (" + link + ")" :
                                                    link;
                                       })
                                       .collect(Collectors.toList());
                var numReviewers = reviewers.size();
                var listing = numReviewers == 1 ?
                    reviewers.get(0) :
                    String.join(", ", reviewers.subList(0, numReviewers - 1));
                if (numReviewers > 1) {
                    listing += " and " + reviewers.get(numReviewers - 1);
                }
                info += " and was reviewed by " + listing;
            }
            info += ".";
            lines.add(info);
            lines.add("");
            lines.add("Thanks,");
            lines.add("J. Duke");

            var prFromFork = fork.createPullRequest(targetRepo.get(),
                                                    "master",
                                                    backportBranch.name(),
                                                    "Backport " + hash.hex(),
                                                    lines);
            var prFromTarget = targetRepo.get().pullRequest(prFromFork.id());
            reply.println("@" + command.user().username() + " backport pull request [#" + prFromTarget.id() + "](" + prFromFork.webUrl() + ") targeting repository [" + targetRepo.get().name() + "](" + targetRepo.get().webUrl() + ") created successfully.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
