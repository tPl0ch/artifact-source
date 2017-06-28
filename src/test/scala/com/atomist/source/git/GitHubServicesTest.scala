package com.atomist.source.git

import java.io.File

import com.atomist.source.FileArtifact.{DefaultMode, ExecutableMode}
import com.atomist.source.file.FileSystemArtifactSourceIdentifier
import com.atomist.source.git.GitHubArtifactSourceLocator.MasterBranch
import com.atomist.source.git.GitHubServices.PullRequestRequest
import com.atomist.source.git.TestConstants._
import com.atomist.source.{Artifact, FileArtifact, SimpleCloudRepoId, StringFileArtifact}
import com.atomist.util.{BinaryDecider, GitRepositoryCloner}
import org.apache.commons.io.{FileUtils, IOUtils}
import org.kohsuke.github.GHIssueState

import scala.collection.JavaConverters._

class GitHubServicesTest extends GitHubMutatorTest(Token) {

  "GitHubServices" should "find valid organization" in {
    ghs.getOrganization(TestOrg) shouldBe defined
  }

  it should "fail to find unknown organization" in {
    ghs.getOrganization("comfoobar") shouldBe empty
  }

  it should "find valid user repository" in {
    ghs.getRepository("satin-scala", "alankstewart") shouldBe defined
  }

  it should "fail to find unknown user repository" in {
    ghs.getRepository("comfoobar", "alankstewart") shouldBe empty
  }

  it should "fail to find unknown organization repository" in {
    ghs.getRepository("unknown-repo", TestOrg) shouldBe empty
  }

  it should "handle null repository" in {
    an[IllegalArgumentException] should be thrownBy ghs.getRepository(null, TestOrg)
  }

  it should "handle empty repository" in {
    an[IllegalArgumentException] should be thrownBy ghs.getRepository(" ", TestOrg)
  }

  it should "handle null organization" in {
    an[IllegalArgumentException] should be thrownBy ghs.getRepository("unknown-repo", null)
  }

  it should "handle empty organization" in {
    an[IllegalArgumentException] should be thrownBy ghs.getRepository("unknown-repo", "")
  }

  it should "delete files with valid path in multi file commit" in {
    val newTempRepo = newPopulatedTemporaryRepo()

    val cri = SimpleCloudRepoId(newTempRepo.getName, newTempRepo.getOwnerName)
    val master = GitHubArtifactSourceLocator(cri, branch = MasterBranch)
    val before = ghs sourceFor master
    before.allFiles should have size 1

    val newBranchName = "add-multi-files-branch"
    ghs.createBranch(newTempRepo, newBranchName, MasterBranch)
    val newBranchSource = GitHubArtifactSourceLocator(cri, newBranchName)

    val tempFiles = createTempFiles(newBranchSource)
    val filesToDelete = tempFiles :+ StringFileArtifact(placeholderFilename("bogusFile"), TestFileContents)

    val files: Seq[FileArtifact] = Seq(
      StringFileArtifact("animals/fox.txt", TestFileContents2),
      StringFileArtifact("animals/fox.txt", TestFileContents2), // Deliberate duplicate
      StringFileArtifact("people/politics.txt", "Now is the time for all good men to come to the aid of their party")
    )
    val multiFileCommitMessage = s"multi file commit at ${System.currentTimeMillis}"
    val fileCommit2 = ghs.commitFiles(
      GitHubSourceUpdateInfo(newBranchSource, multiFileCommitMessage), files.asJava, filesToDelete.asJava)
    fileCommit2.isEmpty shouldBe false

    val masterRead = ghs sourceFor master
    masterRead.allFiles.size should equal(before.allFiles.size)

    val branchRead = ghs sourceFor newBranchSource
    branchRead.allFiles.size should equal(files.size)
    for (f <- files)
      withClue(s"should find newly added file $f") {
        branchRead.findFile(f.path) shouldBe defined
        branchRead.findFile(f.path).get.content should equal(f.content)
      }

    val sha = branchRead.id.asInstanceOf[GitHubArtifactSourceIdentifier].commitSha
    val commitsAfter = newTempRepo.queryCommits().from(sha).list().asList().asScala
    withClue(s"${files.size} files should have been added in single commit") {
      commitsAfter should have size 3
    }
    commitsAfter.map(_.getCommitShortInfo.getMessage) should contain(multiFileCommitMessage)
  }

  it should "create new branch and create and delete pull request via create pull request from delta" in {
    val newTempRepo = newPopulatedTemporaryRepo()
    newTempRepo.createContent("some text".getBytes, "new file 1", "test.txt", "master")
    newTempRepo.createContent("some other text".getBytes, "new file 2", "test2.txt", "master")

    val repo = newTempRepo.getName
    val owner = newTempRepo.getOwnerName
    val cri = SimpleCloudRepoId(repo, owner)
    val prTitle = s"My pull request at ${System.currentTimeMillis}"
    val prBody = "This is the body of my pull request"
    val updatedBranch = s"multi-file-${System.currentTimeMillis}"
    val baseBranch = MasterBranch

    val baseSource = GitHubArtifactSourceLocator(cri, baseBranch)

    val startAs = ghs.sourceFor(baseSource)
    startAs.empty shouldBe false
    val modifiedAs = startAs delete "README.md" delete "test.txt"

    val files: Seq[Artifact] = Seq(
      StringFileArtifact("some.txt", TestFileContents),
      StringFileArtifact("scripts/other.txt", "This file isn't in the root"),
      StringFileArtifact("another/directory/tree/extra.txt", "Nested file"),
      StringFileArtifact("test2.txt", "New content")
    )

    val withAddedFiles = modifiedAs + files

    (withAddedFiles Δ modifiedAs).deltas.isEmpty shouldBe false

    val prr = PullRequestRequest(prTitle, updatedBranch, baseBranch, prBody)
    val prs = ghs.createPullRequestFromChanges(baseSource.repo, baseSource.owner, prr, startAs, withAddedFiles, "Added files and deleted files including README.md")

    prs shouldBe defined
    val pr0 = prs.get
    pr0.number should be > 0
    pr0.isOpen shouldBe true
    pr0.body should equal(prBody)
    pr0.htmlUrl.length should be > 0

    val rc = ghs.createReviewComment(repo, owner, pr0.number, "comment body", pr0.head.sha, "some.txt", 1)
    rc.body should equal("comment body")

    val openPrs = newTempRepo.getPullRequests(GHIssueState.OPEN)
    openPrs.isEmpty shouldBe false
    openPrs.asScala.map(_.getTitle) should contain(prTitle)

    val pr1 = newTempRepo.getPullRequest(pr0.number)
    pr1.getNumber should equal(pr0.number)
    pr1.getBase.getRef should equal(prr.base)
    pr1.getHead.getRef should equal(prr.head)
    pr1.getHtmlUrl should not be null

    val merged = ghs.mergePullRequest(repo, owner, pr1.getNumber, pr1.getTitle, "Merged PR")
    merged shouldBe defined
    merged.get.merged shouldBe true

    val pr2 = newTempRepo.getPullRequest(pr1.getNumber)
    pr2.isMerged shouldBe true

    val endAs = ghs.sourceFor(baseSource)
    endAs.allFiles.size shouldBe 4
  }

  private def createTempFiles(newBranchSource: GitHubArtifactSourceLocator): Seq[FileArtifact] = {
    val files: Seq[FileArtifact] = Seq(
      StringFileArtifact(placeholderFilename("tempFile1"), TestFileContents),
      StringFileArtifact(placeholderFilename("tempFile2"), TestFileContents),
      StringFileArtifact(placeholderFilename("tempFile3"), TestFileContents)
    )
    val multiFileCommitMessage = s"multi temp file commit at ${System.currentTimeMillis}"
    val fileArtifacts = ghs.commitFiles(
      GitHubSourceUpdateInfo(newBranchSource, multiFileCommitMessage), files, Seq.empty)
    fileArtifacts.isEmpty shouldBe false
    fileArtifacts
  }
}
