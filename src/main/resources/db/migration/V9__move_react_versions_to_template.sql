-- React/React-DOM and their type packages used to be seeded as __common__
-- ADD_NPM_DEPENDENCY rows pinned to ^18.3.1, which made picking React 19 in
-- the UI generate a broken project (React 18 packages pinned in package.json).
-- They now live in the baseline package.json template, keyed off the request's
-- reactVersion via {{reactPackageVersion}} / {{reactDomPackageVersion}} /
-- {{reactTypesVersion}} / {{reactDomTypesVersion}}. Remove the legacy seed rows
-- from existing databases so they don't double-pin the version (the rows would
-- otherwise win over the template's value because PackageJsonBuilder applies
-- ADD_NPM_DEPENDENCY rows after rendering the template).
DELETE FROM initializer_build_customization
WHERE dependency_id = '__common__'
  AND project_kind = 'FRONTEND'
  AND customization_type = 'ADD_NPM_DEPENDENCY'
  AND maven_artifact_id IN ('react', 'react-dom', '@types/react', '@types/react-dom');
