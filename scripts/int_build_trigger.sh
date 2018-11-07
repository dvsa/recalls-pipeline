#!/bin/bash
changes=0
echo '' > ${WORKSPACE}/build
cvr_repos=`ls ${WORKSPACE}/repos/ | grep -v \@tmp`
for cvr_repo in ${cvr_repos}; do
  echo "> REPO: ${cvr_repo}"
  cd ${WORKSPACE}/repos/${cvr_repo}
  touch last_commit
  last_commit=$(<last_commit)
  current_commit=`git rev-parse HEAD`
  if [[ $last_commit != $current_commit ]]; then
    echo '>> Changes found.'
    echo ">>> WAS: ${last_commit}"
    echo ">>> IS: ${current_commit}"
    echo $current_commit > last_commit
    changes=1
  else
    echo '>> No changes found.'
  fi
done
echo ''
if [ $changes == 1 ]; then
  echo '> Triggering CVR INT build...'
  echo 'BUILD_INT = true' > ${WORKSPACE}/build
fi
