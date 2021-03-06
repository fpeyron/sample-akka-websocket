import sbt.Keys._
import sbt._
import sbt.complete.DefaultParsers._
import sbt.complete.Parser
import sbtrelease.ReleasePlugin.autoImport.{ReleaseKeys, ReleaseStep}

object SbtGitFlowReleaseCommand {

  lazy val runTest: ReleaseStep = ReleaseStep(
    action = { st: State =>
      if (!st.get(ReleaseKeys.skipTests).getOrElse(false)) {
        val extracted = Project.extract(st)
        val ref = extracted.get(thisProjectRef)
        extracted.runAggregated(test in Test in ref, st)
      } else st
    },
    enableCrossBuild = true
  )


  lazy val initFlow: ReleaseStep = ReleaseStep(
    action = { st: State =>
      val version = st.get(ReleaseKeys.versions).map(_._1).getOrElse(sys.error("No versions are set! Was this release part executed before inquireVersions?"))

      // 1. Synchronisation avec remote
      "git fetch -p origin".! match {
        case 0 => // do nothing
        case _ => sys.error("cannot fetch with origin repository")
      }

      // 2. Check unstagged file on master branch
      "git diff master --quiet master".! match {
        case 0 => // do nothing
        case _ => sys.error("Fails because some file are unstagged on master branch!")
      }

      // 3. Check unstagged file on staging branch
      "git diff staging --quiet staging".! match {
        case 0 => // do nothing
        case _ => sys.error("Fails because some file are unstagged on staging branch!")
      }

      // 4. Check behind file on master branch
      Process("git rev-list master..origin/master --count").!! match {
        case "0\n" => // do nothing
        case _ => sys.error(s"Fails because some commit are behing on master branch! You need to pull master branch")
      }

      // 5. Check behind file on staging branch
      Process("git rev-list staging..origin/staging --count").!! match {
        case "0\n" => // do nothing
        case _ => sys.error("Fails because some commit are behing on staging branch! You need to pull staging branch")
      }

      // 6. Create or reset release branch
      s"git checkout release/$version".! match {
        case 0 => // do nothing
        case _ => s"git checkout -b release/$version staging".! match {
          case 0 => // do nothing
          case _ => sys.error(s"failure on creating release branch release/$version!")
        }
      }

      st
    },
    enableCrossBuild = false
  )


  lazy val mergeFlow: ReleaseStep = ReleaseStep(
    action = { st: State =>
      val version = st.get(ReleaseKeys.versions).map(_._1).getOrElse(sys.error("No versions are set! Was this release part executed before inquireVersions?"))

      // Checkout on master branch
      s"git checkout master".! match {
        case 0 => // do nothing
        case _ => sys.error("Unable to checkout master branch !")
      }

      // 5. Merge release to master
      s"git merge release/$version".! match {
        case 0 => // do nothing
        case _ => sys.error(s"Unable to merge release branch release/$version on master!")
      }

      // Checkout on staging branch
      s"git checkout staging".! match {
        case 0 => // do nothing
        case _ => sys.error("Unable to checkout staging branch !")
      }

      // 5. Merge release to staging
      s"git merge release/$version".! match {
        case 0 => // do nothing
        case _ => sys.error(s"Unable to merge release branch release/$version on master!")
      }

      // Remove release branch
      s"git branch -d release/$version".! match {
        case 0 => // do nothing
        case _ => sys.error(s"Unable to delete release branch release/$version!")
      }

      st
    },
    enableCrossBuild = false
  )


  lazy val pushChanges: ReleaseStep = ReleaseStep(
    action = { st: State =>
      val version = st.get(ReleaseKeys.versions).map(_._1).getOrElse(sys.error("No versions are set! Was this release part executed before inquireVersions?"))

      // Checkout on master branch
      s"git push origin master staging".! match {
        case 0 => // do nothing
        case _ => sys.error("Unable to push master and stagin branches on origin!")
      }

      s"git push origin :release/$version 2>/dev/null"


      // 5. Merge release to master
      s"git push --tag -f".! match {
        case 0 => // do nothing
        case _ => sys.error(s"Unable to push tag $version!")
      }

      st
    },
    enableCrossBuild = false
  )


  lazy val publishDocker: ReleaseStep = ReleaseStep(
    action = { st: State =>
      if (!st.get(ReleaseKeysOption.skipDocker).getOrElse(false)) {

        val cmd = Process("aws ecr get-login --no-include-email").!!
        s"$cmd".! match {
          case 0 => // do nothing
          case _ => sys.error("failure to checkout on master!")
        }

        Parser.parse("docker:publishLocal", st.combinedParser) match {
          case Right(cmd) => cmd()
          case Left(msg) => throw sys.error(s"Invalid programmatic input:\n$msg")
        }
      }

      st
    },
    enableCrossBuild = true
  )


  object ReleaseKeysOption {
    val skipDocker = AttributeKey[Boolean]("releaseSkipDocker")
  }

}