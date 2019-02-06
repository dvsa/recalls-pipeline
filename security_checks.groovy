
@Library('PipelineUtils@master')
import dvsa.aws.mot.jenkins.pipeline.common.RepoFunctions
import dvsa.aws.mot.jenkins.pipeline.common.GlobalValues

// global variables
RepoFunctions repoFunctionsFactory = new RepoFunctions()
GlobalValues globalValuesFactory = new GlobalValues()
Map<String, Map<String, String>> github = globalValuesFactory.GITHUB_REPOS()

Map<String, String> params = [
    branch: APP_BRANCH,
];

String jenkinsBuildLabel = "cvr"
String account = "dvsarecallsdev"
String recallsDir = 'recalls'

def failure(String reason) {
  currentBuild.result = "FAILURE"
  log.fatal(reason)
  error(reason)
}

pipeline {
  agent none
  options {
    ansiColor('xterm')
    timestamps()
    timeout(time: 1, unit: 'HOURS')
    buildDiscarder(
      logRotator(
        daysToKeepStr: '7'
      )
    )
    disableConcurrentBuilds()
  }
  stages {
    stage('Check NPM dependencies') {
      failFast true
      agent { node { label "${jenkinsBuildLabel} && ${account}" } }
      steps {
        script {
          dir(recallsDir) {
            if (!repoFunctionsFactory.checkoutGitRepo(
              github.cvr_app.url,
              params.branch,
              github.cvr_app.name,
              globalValuesFactory.SSH_DEPLOY_GIT_CREDS_ID
            )) {
              failure("Failed to clone repository ${github.cvr_app.url}; branch: ${params.branch}")
            }
            dir('recalls-app') {
              sh 'npm install'
              sh 'npm run clean'
              sh 'npm run install:all'
              if (sh (script: 'npm run retire', returnStatus: true)) {
                failure("Dependency check has failed. Check the logs for vulnerable dependencies.")
              }
            }
          }
        }
      }
    }
  }
}
